package com.itantra.radio.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale
import java.util.UUID

class AndroidSystemTtsEngine(
    context: Context,
    private val locale: Locale,
    override val languageCode: String,
) : TtsEngine {

    private var ready = false
    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        ready = status == TextToSpeech.SUCCESS
        if (ready) tts.language = locale
    }

    override fun speak(text: String, isAlert: Boolean) {
        if (!ready || text.isBlank()) return
        val queueMode = if (isAlert) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        tts.speak(text, queueMode, null, UUID.randomUUID().toString())
    }

    override fun stop() {
        tts.stop()
        tts.shutdown()
    }
}
