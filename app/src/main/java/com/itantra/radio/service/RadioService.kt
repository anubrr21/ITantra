package com.itantra.radio.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.itantra.radio.R
import com.itantra.radio.audio.AudioCapturer
import com.itantra.radio.audio.AudioPlayer
import com.itantra.radio.network.BluetoothClassicTransport
import com.itantra.radio.network.Transport
import com.itantra.radio.network.TransportPeer
import com.itantra.radio.network.WifiDirectTransport
import com.itantra.radio.ptt.PttController
import com.itantra.radio.ptt.PttMode
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

enum class RadioLink { WIFI_DIRECT, BLUETOOTH_CLASSIC }

class RadioService : Service() {

    inner class LocalBinder : Binder() {
        fun getService(): RadioService = this@RadioService
    }

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val pttController = PttController()
    private val audioCapturer = AudioCapturer()
    private val audioPlayer = AudioPlayer()

    private val _transport = MutableStateFlow<Transport?>(null)
    val transportFlow: StateFlow<Transport?> = _transport.asStateFlow()

    private var captureJob: Job? = null
    private var receiveJob: Job? = null
    private var watchdogJob: Job? = null
    private var lastPeerFrameAtMs = 0L

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
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

    private fun applyCaptureState() {
        if (pttController.shouldCaptureMic) startCapture() else stopCapture()
    }

    private fun startCapture() {
        if (captureJob?.isActive == true) return
        val transport = _transport.value ?: return
        captureJob = serviceScope.launch {
            audioCapturer.capture().onEach { frame -> transport.send(frame) }.launchIn(this)
        }
    }

    private fun stopCapture() {
        captureJob?.cancel()
        captureJob = null
    }

    private fun listenForIncomingAudio(transport: Transport) {
        receiveJob?.cancel()
        watchdogJob?.cancel()
        audioPlayer.start()

        receiveJob = transport.incomingFrames()
            .onEach { frame ->
                lastPeerFrameAtMs = System.currentTimeMillis()
                pttController.onPeerTransmitting(true)
                audioPlayer.playFrame(frame)
            }
            .launchIn(serviceScope)

        watchdogJob = serviceScope.launch {
            while (isActive) {
                delay(200)
                if (lastPeerFrameAtMs != 0L && System.currentTimeMillis() - lastPeerFrameAtMs > PEER_SILENCE_TIMEOUT_MS) {
                    pttController.onPeerTransmitting(false)
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
