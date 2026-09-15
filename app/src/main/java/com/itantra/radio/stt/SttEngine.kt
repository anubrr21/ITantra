package com.itantra.radio.stt

interface SttEngine {
    val languageCode: String

    fun start()
    fun acceptAudioFrame(frame: ByteArray)
    fun endUtterance()
    fun stop()
    fun setOnResult(callback: (text: String) -> Unit)
}
