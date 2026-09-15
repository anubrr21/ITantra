package com.itantra.radio.tts

interface TtsEngine {
    val languageCode: String

    fun speak(text: String, isAlert: Boolean)
    fun stop()
}
