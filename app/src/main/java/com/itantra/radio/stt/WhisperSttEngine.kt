package com.itantra.radio.stt

import android.content.Context
import android.util.Log
import com.itantra.radio.audio.AudioConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import java.io.ByteArrayOutputStream

class WhisperSttEngine(
    context: Context,
    override val languageCode: String,
    threads: Int = DEFAULT_THREADS,
) : SttEngine {

    companion object {
        const val DEFAULT_THREADS = 2
        private const val MIN_UTTERANCE_SAMPLES = AudioConfig.SAMPLE_RATE_HZ / 5
        private const val LOG_TAG = "WhisperSttEngine"
    }

    private val files = WhisperAssetProvisioner.ensureFiles(context, languageCode)
    private val recorder = UtteranceRecorder(context, "whisper")

    private val recognizer = OfflineRecognizer(
        config = OfflineRecognizerConfig(
            modelConfig = OfflineModelConfig(
                whisper = OfflineWhisperModelConfig(
                    encoder = files.encoder.absolutePath,
                    decoder = files.decoder.absolutePath,
                    language = languageCode,
                    task = "transcribe",
                ),
                tokens = files.tokens.absolutePath,
                numThreads = threads,
                debug = false,
                provider = "cpu",
                modelType = "whisper",
            ),
            decodingMethod = "greedy_search",
        ),
    )

    private val bufferLock = Any()
    private val recognizerLock = Any()
    private val bufferedPcm = ByteArrayOutputStream()

    @Volatile
    private var onResult: ((String) -> Unit)? = null

    @Volatile
    private var released = false

    override fun start() {
        synchronized(bufferLock) { bufferedPcm.reset() }
    }

    override fun acceptAudioFrame(frame: ByteArray) {
        synchronized(bufferLock) { bufferedPcm.write(frame) }
    }

    override fun endUtterance() {
        val pcm = synchronized(bufferLock) {
            val snapshot = bufferedPcm.toByteArray()
            bufferedPcm.reset()
            snapshot
        }
        val samples = FloatArray(pcm.size / 2)
        if (samples.size < MIN_UTTERANCE_SAMPLES) return
        recorder.save(pcm)
        for (i in samples.indices) {
            val lo = pcm[i * 2].toInt() and 0xFF
            val hi = pcm[i * 2 + 1].toInt()
            samples[i] = ((hi shl 8) or lo).toShort() / 32768f
        }

        val text = transcribe(samples)
        if (text.isNotBlank()) onResult?.invoke(text)
    }

    override fun stop() {
        synchronized(bufferLock) { bufferedPcm.reset() }
        synchronized(recognizerLock) {
            if (!released) {
                released = true
                recognizer.release()
            }
        }
    }

    override fun setOnResult(callback: (String) -> Unit) {
        onResult = callback
    }

    fun transcribe(samples: FloatArray): String = synchronized(recognizerLock) {
        if (released) return ""
        val start = System.nanoTime()
        val stream = recognizer.createStream()
        try {
            stream.acceptWaveform(samples, AudioConfig.SAMPLE_RATE_HZ)
            recognizer.decode(stream)
            val raw = recognizer.getResult(stream).text
            val elapsedMs = (System.nanoTime() - start) / 1_000_000
            Log.d(LOG_TAG, "audio_ms=${samples.size * 1000L / AudioConfig.SAMPLE_RATE_HZ} decode_ms=$elapsedMs")
            SttTextFilter.clean(raw)
        } finally {
            stream.release()
        }
    }
}
