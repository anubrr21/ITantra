package com.itantra.radio.stt

import com.itantra.radio.audio.AudioConfig
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer

class VoskSttEngine(
    private val model: Model,
    override val languageCode: String,
) : SttEngine {

    private var recognizer: Recognizer? = null
    private var onResult: ((String) -> Unit)? = null

    override fun start() {
        recognizer = newRecognizer()
    }

    override fun acceptAudioFrame(frame: ByteArray) {
        recognizer?.acceptWaveForm(frame, frame.size)
    }

    override fun endUtterance() {
        val current = recognizer ?: return
        val text = runCatching { JSONObject(current.finalResult).optString("text", "") }.getOrDefault("")
        current.close()
        recognizer = newRecognizer()
        if (text.isNotBlank()) onResult?.invoke(text)
    }

    override fun stop() {
        recognizer?.close()
        recognizer = null
    }

    override fun setOnResult(callback: (String) -> Unit) {
        onResult = callback
    }

    private fun newRecognizer(): Recognizer? =
        runCatching { Recognizer(model, AudioConfig.SAMPLE_RATE_HZ.toFloat()) }.getOrNull()
}
