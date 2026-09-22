package com.itantra.radio.network

import java.nio.ByteBuffer
import java.nio.ByteOrder

sealed interface RadioFrame {
    class Audio(val pcm: ByteArray) : RadioFrame
    data class Text(
        val text: String,
        val isAlert: Boolean = false,
        val languageCode: String? = null,
        val speechEndEpochMs: Long? = null,
        val sentEpochMs: Long? = null,
    ) : RadioFrame
    data class PeerSpeaking(val speaking: Boolean) : RadioFrame
    data class ClockSyncPing(val originEpochMs: Long) : RadioFrame
    data class ClockSyncPong(val originEpochMs: Long, val replyEpochMs: Long) : RadioFrame
}

object RadioFrameCodec {
    private const val TYPE_AUDIO: Byte = 0
    private const val TYPE_TEXT: Byte = 1
    private const val TYPE_ALERT_TEXT: Byte = 2
    private const val TYPE_TEXT_WITH_LANGUAGE: Byte = 3
    private const val TYPE_ALERT_TEXT_WITH_LANGUAGE: Byte = 4
    private const val TYPE_PEER_SPEAKING_STARTED: Byte = 5
    private const val TYPE_PEER_SPEAKING_STOPPED: Byte = 6
    private const val TYPE_TEXT_WITH_TIMING: Byte = 7
    private const val TYPE_ALERT_TEXT_WITH_TIMING: Byte = 8
    private const val TYPE_CLOCK_SYNC_PING: Byte = 9
    private const val TYPE_CLOCK_SYNC_PONG: Byte = 10
    private const val LANGUAGE_TERMINATOR: Byte = 0
    private const val MAX_LANGUAGE_CODE_BYTES = 16
    private const val LONG_BYTES = 8
    private const val TIMING_BYTES = LONG_BYTES * 2

    fun encode(frame: RadioFrame): ByteArray = when (frame) {
        is RadioFrame.Audio -> byteArrayOf(TYPE_AUDIO) + frame.pcm
        is RadioFrame.Text -> encodeText(frame)
        is RadioFrame.PeerSpeaking ->
            byteArrayOf(if (frame.speaking) TYPE_PEER_SPEAKING_STARTED else TYPE_PEER_SPEAKING_STOPPED)
        is RadioFrame.ClockSyncPing -> byteArrayOf(TYPE_CLOCK_SYNC_PING) + longBytes(frame.originEpochMs)
        is RadioFrame.ClockSyncPong ->
            byteArrayOf(TYPE_CLOCK_SYNC_PONG) + longBytes(frame.originEpochMs) + longBytes(frame.replyEpochMs)
    }

    private fun encodeText(frame: RadioFrame.Text): ByteArray {
        val body = frame.text.toByteArray(Charsets.UTF_8)
        val language = frame.languageCode
        if (language.isNullOrEmpty()) {
            return byteArrayOf(if (frame.isAlert) TYPE_ALERT_TEXT else TYPE_TEXT) + body
        }
        val languageBytes = language.toByteArray(Charsets.US_ASCII) + byteArrayOf(LANGUAGE_TERMINATOR)
        val speechEnd = frame.speechEndEpochMs
        val sent = frame.sentEpochMs
        if (speechEnd != null && sent != null) {
            val tag = if (frame.isAlert) TYPE_ALERT_TEXT_WITH_TIMING else TYPE_TEXT_WITH_TIMING
            return byteArrayOf(tag) + languageBytes + longBytes(speechEnd) + longBytes(sent) + body
        }
        val tag = if (frame.isAlert) TYPE_ALERT_TEXT_WITH_LANGUAGE else TYPE_TEXT_WITH_LANGUAGE
        return byteArrayOf(tag) + languageBytes + body
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
            TYPE_TEXT_WITH_TIMING -> decodeTextWithTiming(payload, isAlert = false)
            TYPE_ALERT_TEXT_WITH_TIMING -> decodeTextWithTiming(payload, isAlert = true)
            TYPE_CLOCK_SYNC_PING -> decodeClockSyncPing(payload)
            TYPE_CLOCK_SYNC_PONG -> decodeClockSyncPong(payload)
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

    private fun decodeTextWithTiming(payload: ByteArray, isAlert: Boolean): RadioFrame? {
        val end = payload.indexOf(LANGUAGE_TERMINATOR)
        if (end < 0 || end > MAX_LANGUAGE_CODE_BYTES) return null
        val timingStart = end + 1
        if (timingStart + TIMING_BYTES > payload.size) return null
        val language = String(payload, 0, end, Charsets.US_ASCII)
        val buffer = ByteBuffer.wrap(payload, timingStart, TIMING_BYTES).order(ByteOrder.BIG_ENDIAN)
        val speechEndEpochMs = buffer.long
        val sentEpochMs = buffer.long
        val textStart = timingStart + TIMING_BYTES
        val text = String(payload, textStart, payload.size - textStart, Charsets.UTF_8)
        return RadioFrame.Text(text, isAlert, language.ifEmpty { null }, speechEndEpochMs, sentEpochMs)
    }

    private fun decodeClockSyncPing(payload: ByteArray): RadioFrame? {
        if (payload.size != LONG_BYTES) return null
        return RadioFrame.ClockSyncPing(readLong(payload, 0))
    }

    private fun decodeClockSyncPong(payload: ByteArray): RadioFrame? {
        if (payload.size != LONG_BYTES * 2) return null
        return RadioFrame.ClockSyncPong(readLong(payload, 0), readLong(payload, LONG_BYTES))
    }

    private fun longBytes(value: Long): ByteArray =
        ByteBuffer.allocate(LONG_BYTES).order(ByteOrder.BIG_ENDIAN).putLong(value).array()

    private fun readLong(payload: ByteArray, offset: Int): Long =
        ByteBuffer.wrap(payload, offset, LONG_BYTES).order(ByteOrder.BIG_ENDIAN).long
}
