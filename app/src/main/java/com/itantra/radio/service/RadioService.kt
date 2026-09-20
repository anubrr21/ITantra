package com.itantra.radio.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.itantra.radio.R
import com.itantra.radio.audio.AudioCapturer
import com.itantra.radio.audio.AudioPlayer
import com.itantra.radio.lang.SupportedLanguage
import com.itantra.radio.network.BluetoothClassicTransport
import com.itantra.radio.network.RadioFrame
import com.itantra.radio.network.RadioFrameCodec
import com.itantra.radio.network.Transport
import com.itantra.radio.network.TransportPeer
import com.itantra.radio.network.WifiDirectTransport
import com.itantra.radio.ptt.PttController
import com.itantra.radio.ptt.PttMode
import com.itantra.radio.stt.IndicConformerSttEngine
import com.itantra.radio.stt.SttEngine
import com.itantra.radio.stt.VoskModelProvisioner
import com.itantra.radio.stt.WhisperAssetProvisioner
import com.itantra.radio.stt.WhisperSttEngine
import com.itantra.radio.stt.VoskSttEngine
import com.itantra.radio.tts.AndroidSystemTtsEngine
import com.itantra.radio.tts.IndicTtsAssetProvisioner
import com.itantra.radio.tts.IndicTtsEngine
import com.itantra.radio.tts.PiperAssetProvisioner
import com.itantra.radio.tts.PiperTtsEngine
import com.itantra.radio.tts.TtsEngine
import com.itantra.radio.vad.WebRtcVoiceActivityDetector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val CHANNEL_ID = "itantra_radio"
private const val NOTIFICATION_ID = 1
private const val PEER_SILENCE_TIMEOUT_MS = 500L
private const val LOG_TAG = "RadioService"
private const val LOG_EVERY_N_FRAMES = 50

enum class RadioLink { WIFI_DIRECT, BLUETOOTH_CLASSIC }

enum class TransmissionMode { RAW_AUDIO, VOICE_TEXT }

class RadioService : Service() {

    inner class LocalBinder : Binder() {
        fun getService(): RadioService = this@RadioService
    }

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val pttController = PttController()
    private val audioCapturer = AudioCapturer()
    private val audioPlayer = AudioPlayer()
    private val vad = WebRtcVoiceActivityDetector()
    @Volatile private var wasSpeaking = false

    private val _transport = MutableStateFlow<Transport?>(null)
    val transportFlow: StateFlow<Transport?> = _transport.asStateFlow()

    private val _transmissionMode = MutableStateFlow(TransmissionMode.RAW_AUDIO)
    val transmissionModeFlow: StateFlow<TransmissionMode> = _transmissionMode.asStateFlow()

    private val _language = MutableStateFlow(SupportedLanguage.ENGLISH)
    val languageFlow: StateFlow<SupportedLanguage> = _language.asStateFlow()

    private val _recognizedText = MutableStateFlow("")
    val recognizedTextFlow: StateFlow<String> = _recognizedText.asStateFlow()

    private val _alertMode = MutableStateFlow(false)
    val alertModeFlow: StateFlow<Boolean> = _alertMode.asStateFlow()

    private val _voiceStatus = MutableStateFlow("")
    val voiceStatusFlow: StateFlow<String> = _voiceStatus.asStateFlow()
    private var sttLabel = "loading"
    private var ttsLabel = "system voice"

    private var sttEngine: SttEngine? = null
    private var ttsEngine: TtsEngine? = null

    private var captureJob: Job? = null
    private var receiveJob: Job? = null
    private var watchdogJob: Job? = null
    private var lastPeerFrameAtMs = 0L
    private var audioFramesSent = 0L
    private var audioFramesReceived = 0L

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        setLanguage(SupportedLanguage.ENGLISH)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    fun useLink(link: RadioLink) {
        _transport.value?.disconnect()
        val transport = when (link) {
            RadioLink.WIFI_DIRECT -> WifiDirectTransport(applicationContext, serviceScope)
            RadioLink.BLUETOOTH_CLASSIC -> BluetoothClassicTransport(applicationContext, serviceScope)
        }
        _transport.value = transport
        listenForIncomingAudio(transport)
    }

    fun host() = _transport.value?.becomeHost()
    fun discover() = _transport.value?.startDiscovery()
    fun connectTo(peer: TransportPeer) = _transport.value?.connectTo(peer)

    fun setPttMode(mode: PttMode) {
        pttController.setMode(mode)
        applyCaptureState()
    }

    fun onPttPressed() {
        pttController.onPttPressed()
        applyCaptureState()
    }

    fun onPttReleased() {
        pttController.onPttReleased()
        applyCaptureState()
    }

    fun setTransmissionMode(mode: TransmissionMode) {
        _transmissionMode.value = mode
        wasSpeaking = false
    }

    fun setAlertMode(enabled: Boolean) {
        _alertMode.value = enabled
    }

    fun setLanguage(language: SupportedLanguage) {
        _language.value = language

        sttEngine?.stop()
        sttEngine = null
        sttLabel = "loading"
        ttsLabel = "system voice"
        refreshVoiceStatus(language)

        ttsEngine?.stop()
        ttsEngine = AndroidSystemTtsEngine(applicationContext, language.ttsLocale, language.code)

        serviceScope.launch(Dispatchers.IO) {
            loadSttEngine(language)
            upgradeToNeuralTts(language)
            refreshVoiceStatus(language)
        }
    }

    private fun refreshVoiceStatus(language: SupportedLanguage) {
        if (_language.value != language) return
        _voiceStatus.value = when (sttLabel) {
            "loading" -> "Loading ${language.displayName} speech models, please wait..."
            "unavailable" -> "${language.displayName}: speech recognizer unavailable, voice: $ttsLabel"
            else -> "${language.displayName} ready - recognizer: $sttLabel, voice: $ttsLabel"
        }
    }

    private fun loadSttEngine(language: SupportedLanguage) {
        if (language == SupportedLanguage.ENGLISH && WhisperAssetProvisioner.isBundled(applicationContext, language.code)) {
            val engine = runCatching { WhisperSttEngine(applicationContext, language.code) }
                .onFailure { Log.e(LOG_TAG, "Whisper failed, falling back to Vosk", it) }
                .getOrNull()
            if (engine != null) {
                engine.setOnResult { text -> onRecognizedText(text) }
                engine.start()
                sttEngine = engine
                sttLabel = "neural (Whisper)"
                return
            }
        }

        if (language == SupportedLanguage.HINDI) {
            val engine = runCatching { IndicConformerSttEngine(applicationContext, language.code) }
                .onFailure { Log.e(LOG_TAG, "IndicConformer failed, falling back to Vosk", it) }
                .getOrNull()
            if (engine != null) {
                engine.setOnResult { text -> onRecognizedText(text) }
                engine.start()
                sttEngine = engine
                sttLabel = "neural (IndicConformer)"
                return
            }
        }

        VoskModelProvisioner.unpack(
            applicationContext,
            language.voskAssetFolder,
            onReady = { model ->
                val engine = VoskSttEngine(model, language.code)
                engine.setOnResult { text -> onRecognizedText(text) }
                engine.start()
                sttEngine = engine
                sttLabel = "basic (Vosk)"
            },
            onError = {
                Log.e(LOG_TAG, "Vosk model failed to load")
                sttLabel = "unavailable"
            },
        )
    }

    private fun buildNeuralTts(language: SupportedLanguage): TtsEngine? {
        if (language != SupportedLanguage.HINDI) return null
        if (PiperAssetProvisioner.isBundled(applicationContext, language.code)) {
            val piper = runCatching { PiperTtsEngine(applicationContext, language.code) }
                .onFailure { Log.e(LOG_TAG, "Piper failed to load", it) }
                .getOrNull()
            if (piper != null) return piper
        }
        if (IndicTtsAssetProvisioner.isBundled(applicationContext, language.code)) {
            return runCatching { IndicTtsEngine(applicationContext, language.code) }.getOrNull()
        }
        return null
    }

    private fun upgradeToNeuralTts(language: SupportedLanguage) {
        val neural = buildNeuralTts(language) ?: return
        if (_language.value != language) {
            neural.stop()
            return
        }
        val previous = ttsEngine
        ttsEngine = neural
        ttsLabel = if (neural is PiperTtsEngine) "neural (Piper)" else "neural (FastPitch)"
        previous?.stop()
    }

    private fun applyCaptureState() {
        if (pttController.shouldCaptureMic) startCapture() else stopCapture()
    }

    private fun startCapture() {
        if (captureJob?.isActive == true) return
        val transport = _transport.value ?: return
        captureJob = serviceScope.launch {
            audioCapturer.capture().onEach { frame -> if (isActive) handleCapturedFrame(transport, frame) }.launchIn(this)
        }
    }

    private fun stopCapture() {
        captureJob?.cancel()
        captureJob = null
        finishPendingUtterance()
    }

    private fun finishPendingUtterance() {
        if (_transmissionMode.value != TransmissionMode.VOICE_TEXT || !wasSpeaking) return
        wasSpeaking = false
        val engine = sttEngine ?: return
        serviceScope.launch(Dispatchers.Default) {
            Log.d(LOG_TAG, "ptt released while speaking, finishing utterance")
            engine.endUtterance()
        }
    }

    private fun handleCapturedFrame(transport: Transport, frame: ByteArray) {
        when (_transmissionMode.value) {
            TransmissionMode.RAW_AUDIO -> {
                transport.send(RadioFrameCodec.encode(RadioFrame.Audio(frame)))
                if (++audioFramesSent % LOG_EVERY_N_FRAMES == 0L) Log.d(LOG_TAG, "tx audio frames=$audioFramesSent")
            }
            TransmissionMode.VOICE_TEXT -> {
                val speaking = vad.isSpeech(frame)
                if (speaking) {
                    if (!wasSpeaking) Log.d(LOG_TAG, "vad speech start, stt engine ready=${sttEngine != null}")
                    sttEngine?.acceptAudioFrame(frame)
                } else if (wasSpeaking) {
                    Log.d(LOG_TAG, "vad speech end, finishing utterance")
                    sttEngine?.endUtterance()
                }
                wasSpeaking = speaking
            }
        }
    }

    private fun onRecognizedText(text: String) {
        _recognizedText.value = text
        Log.d(LOG_TAG, "stt result chars=${text.length} alert=${_alertMode.value}")
        _transport.value?.send(RadioFrameCodec.encode(RadioFrame.Text(text, isAlert = _alertMode.value)))
    }

    private fun listenForIncomingAudio(transport: Transport) {
        receiveJob?.cancel()
        watchdogJob?.cancel()
        audioPlayer.start()

        receiveJob = transport.incomingFrames()
            .onEach { bytes ->
                lastPeerFrameAtMs = System.currentTimeMillis()
                pttController.onPeerTransmitting(true)
                when (val frame = RadioFrameCodec.decode(bytes)) {
                    is RadioFrame.Audio -> {
                        audioPlayer.playFrame(frame.pcm)
                        if (++audioFramesReceived % LOG_EVERY_N_FRAMES == 0L) Log.d(LOG_TAG, "rx audio frames=$audioFramesReceived")
                    }
                    is RadioFrame.Text -> {
                        Log.d(LOG_TAG, "rx text alert=${frame.isAlert} chars=${frame.text.length}")
                        ttsEngine?.speak(frame.text, frame.isAlert)
                    }
                    null -> Unit
                }
            }
            .launchIn(serviceScope)

        watchdogJob = serviceScope.launch {
            while (isActive) {
                delay(200)
                if (lastPeerFrameAtMs != 0L && System.currentTimeMillis() - lastPeerFrameAtMs > PEER_SILENCE_TIMEOUT_MS) {
                    pttController.onPeerTransmitting(false)
                    audioPlayer.endOfBurst()
                    lastPeerFrameAtMs = 0L
                }
            }
        }
    }

    override fun onDestroy() {
        stopCapture()
        receiveJob?.cancel()
        watchdogJob?.cancel()
        audioPlayer.stop()
        vad.close()
        sttEngine?.stop()
        ttsEngine?.stop()
        _transport.value?.disconnect()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.radio_service_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.radio_service_notification_title))
            .setContentText(getString(R.string.radio_service_notification_text))
            .setSmallIcon(R.drawable.ic_launcher_radio)
            .setOngoing(true)
            .build()
}
