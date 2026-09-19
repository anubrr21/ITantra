package com.itantra.radio.stt

import com.itantra.radio.audio.AudioConfig
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer

class VoskSttEngine(
    private val model: Model,
    override val languageCode: String,
) : SttEngine {

    private val lock = Any()
    private var recognizer: Recognizer? = null

    @Volatile
    private var onResult: ((String) -> Unit)? = null

    override fun start() {
        synchronized(lock) {
            recognizer?.close()
            recognizer = newRecognizer()
        }
    }

    override fun acceptAudioFrame(frame: ByteArray) {
        synchronized(lock) {
            recognizer?.acceptWaveForm(frame, frame.size)
        }
    }

    override fun endUtterance() {
        val text = synchronized(lock) {
            val current = recognizer ?: return
            val recognized = runCatching { JSONObject(current.finalResult).optString("text", "") }.getOrDefault("")
            current.close()
            recognizer = newRecognizer()
            recognized
        }
        if (text.isNotBlank()) onResult?.invoke(text)
    }

    override fun stop() {
        synchronized(lock) {
            recognizer?.close()
            recognizer = null
        }
    }

    override fun setOnResult(callback: (String) -> Unit) {
        onResult = callback
    }

    private fun newRecognizer(): Recognizer? =
        runCatching { Recognizer(model, AudioConfig.SAMPLE_RATE_HZ.toFloat()) }.getOrNull()
}
