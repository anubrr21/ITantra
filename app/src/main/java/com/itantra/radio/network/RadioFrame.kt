package com.itantra.radio.network

sealed interface RadioFrame {
    class Audio(val pcm: ByteArray) : RadioFrame
    data class Text(
        val text: String,
        val isAlert: Boolean = false,
        val languageCode: String? = null,
    ) : RadioFrame
    data class PeerSpeaking(val speaking: Boolean) : RadioFrame
}

object RadioFrameCodec {
    private const val TYPE_AUDIO: Byte = 0
    private const val TYPE_TEXT: Byte = 1
    private const val TYPE_ALERT_TEXT: Byte = 2
    private const val TYPE_TEXT_WITH_LANGUAGE: Byte = 3
    private const val TYPE_ALERT_TEXT_WITH_LANGUAGE: Byte = 4
    private const val TYPE_PEER_SPEAKING_STARTED: Byte = 5
    private const val TYPE_PEER_SPEAKING_STOPPED: Byte = 6
    private const val LANGUAGE_TERMINATOR: Byte = 0
    private const val MAX_LANGUAGE_CODE_BYTES = 16

    fun encode(frame: RadioFrame): ByteArray = when (frame) {
        is RadioFrame.Audio -> byteArrayOf(TYPE_AUDIO) + frame.pcm
        is RadioFrame.Text -> encodeText(frame)
        is RadioFrame.PeerSpeaking ->
            byteArrayOf(if (frame.speaking) TYPE_PEER_SPEAKING_STARTED else TYPE_PEER_SPEAKING_STOPPED)
    }

    private fun encodeText(frame: RadioFrame.Text): ByteArray {
        val body = frame.text.toByteArray(Charsets.UTF_8)
        val language = frame.languageCode
        if (language.isNullOrEmpty()) {
            return byteArrayOf(if (frame.isAlert) TYPE_ALERT_TEXT else TYPE_TEXT) + body
        }
        val tag = if (frame.isAlert) TYPE_ALERT_TEXT_WITH_LANGUAGE else TYPE_TEXT_WITH_LANGUAGE
        return byteArrayOf(tag) + language.toByteArray(Charsets.US_ASCII) + byteArrayOf(LANGUAGE_TERMINATOR) + body
    }

    fun decode(bytes: ByteArray): RadioFrame? {
        if (bytes.isEmpty()) return null
        val payload = bytes.copyOfRange(1, bytes.size)
        return when (bytes[0]) {
            TYPE_AUDIO -> RadioFrame.Audio(payload)
            TYPE_TEXT -> RadioFrame.Text(String(payload, Charsets.UTF_8), isAlert = false)
            TYPE_ALERT_TEXT -> RadioFrame.Text(String(payload, Charsets.UTF_8), isAlert = true)
            TYPE_TEXT_WITH_LANGUAGE -> decodeTextWithLanguage(payload, isAlert = false)
            TYPE_ALERT_TEXT_WITH_LANGUAGE -> decodeTextWithLanguage(payload, isAlert = true)
            TYPE_PEER_SPEAKING_STARTED -> RadioFrame.PeerSpeaking(true)
            TYPE_PEER_SPEAKING_STOPPED -> RadioFrame.PeerSpeaking(false)
            else -> null
        }
    }

    private fun decodeTextWithLanguage(payload: ByteArray, isAlert: Boolean): RadioFrame? {
        val end = payload.indexOf(LANGUAGE_TERMINATOR)
        if (end < 0 || end > MAX_LANGUAGE_CODE_BYTES) return null
        val language = String(payload, 0, end, Charsets.US_ASCII)
        val text = String(payload, end + 1, payload.size - end - 1, Charsets.UTF_8)
        return RadioFrame.Text(text, isAlert, language.ifEmpty { null })
    }
}
