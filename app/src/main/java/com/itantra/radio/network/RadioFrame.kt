package com.itantra.radio.network

sealed interface RadioFrame {
    class Audio(val pcm: ByteArray) : RadioFrame
    data class Text(val text: String, val isAlert: Boolean = false) : RadioFrame
}

object RadioFrameCodec {
    private const val TYPE_AUDIO: Byte = 0
    private const val TYPE_TEXT: Byte = 1
    private const val TYPE_ALERT_TEXT: Byte = 2

    fun encode(frame: RadioFrame): ByteArray = when (frame) {
        is RadioFrame.Audio -> byteArrayOf(TYPE_AUDIO) + frame.pcm
        is RadioFrame.Text -> byteArrayOf(if (frame.isAlert) TYPE_ALERT_TEXT else TYPE_TEXT) +
            frame.text.toByteArray(Charsets.UTF_8)
    }

    fun decode(bytes: ByteArray): RadioFrame? {
        if (bytes.isEmpty()) return null
        val payload = bytes.copyOfRange(1, bytes.size)
        return when (bytes[0]) {
            TYPE_AUDIO -> RadioFrame.Audio(payload)
            TYPE_TEXT -> RadioFrame.Text(String(payload, Charsets.UTF_8), isAlert = false)
            TYPE_ALERT_TEXT -> RadioFrame.Text(String(payload, Charsets.UTF_8), isAlert = true)
            else -> null
        }
    }
}
