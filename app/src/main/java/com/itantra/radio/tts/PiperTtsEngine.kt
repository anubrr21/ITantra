package com.itantra.radio.tts

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig

class PiperTtsEngine(
    context: Context,
    override val languageCode: String,
    private val speakerId: Int = 0,
    private val speed: Float = DEFAULT_SPEED,
    threads: Int = DEFAULT_THREADS,
) : TtsEngine {

    companion object {
        const val DEFAULT_SPEED = 1.0f
        const val DEFAULT_THREADS = 2
        const val MAX_CHUNK_CHARS = 140
        private const val TAIL_SILENCE_MS = 90
        private const val LOG_TAG = "PiperTtsEngine"
    }

    private val files = PiperAssetProvisioner.ensureFiles(context, languageCode)

    private val tts = OfflineTts(
        config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                vits = OfflineTtsVitsModelConfig(
                    model = files.model.absolutePath,
                    tokens = files.tokens.absolutePath,
                    dataDir = files.espeakData.absolutePath,
                ),
                numThreads = threads,
                debug = false,
                provider = "cpu",
            ),
            maxNumSentences = 1,
        ),
    )

    val sampleRateHz: Int = tts.sampleRate()

    private val synthesisLock = Any()
    private val volumeGuard = AlertVolumeGuard(context)
    private val clipPlayer = ClipPlayer(sampleRateHz)

    private val scheduler = SpeechScheduler(
        splitter = { text -> IndicTtsTextPrep.splitIntoSpeakableChunks(text, MAX_CHUNK_CHARS) },
        synthesize = ::synthesizeChunk,
        playClip = clipPlayer::play,
        onAlertStart = volumeGuard::raiseToMax,
        onAlertEnd = volumeGuard::restore,
    )

    override fun speak(text: String, isAlert: Boolean) {
        if (text.isBlank()) return
        scheduler.enqueue(text, isAlert)
    }

    override val isSpeaking: Boolean
        get() = scheduler.isBusy()

    override fun stop() {
        val drained = scheduler.shutdown()
        volumeGuard.restore()
        if (drained) {
            synchronized(synthesisLock) { tts.release() }
        }
    }

    fun synthesizeToPcm(text: String): ShortArray =
        Pcm16.concat(IndicTtsTextPrep.splitIntoSpeakableChunks(text, MAX_CHUNK_CHARS).map { synthesizeChunk(it) })

    private fun synthesizeChunk(chunk: String): ShortArray {
        val start = System.nanoTime()
        val audio = synchronized(synthesisLock) { tts.generate(chunk, speakerId, speed) }
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        val audioMs = audio.samples.size * 1000L / audio.sampleRate
        Log.d(LOG_TAG, "chars=${chunk.length} synth_ms=$elapsedMs audio_ms=$audioMs")
        return Pcm16.fromFloat(audio.samples, audio.sampleRate, TAIL_SILENCE_MS)
    }
}
