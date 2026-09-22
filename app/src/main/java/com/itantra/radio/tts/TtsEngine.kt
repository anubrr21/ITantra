package com.itantra.radio.tts

interface TtsEngine {
    val languageCode: String

    val isSpeaking: Boolean

    fun speak(text: String, isAlert: Boolean)
    fun stop()
}
