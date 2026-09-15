package com.itantra.radio.network

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Length-prefixed framing over a raw stream socket. Both WiFi Direct (TCP) and
 * Bluetooth Classic (RFCOMM) give us an unstructured byte stream, not message
 * boundaries, so every payload (an audio chunk today, a recognized-text message once
 * STT lands) is written as [4-byte big-endian length][payload bytes].
 */
object FrameCodec {
    private const val MAX_FRAME_BYTES = 1 shl 20 // 1 MiB guard against a corrupt stream

    @Throws(IOException::class)
    fun writeFrame(output: OutputStream, payload: ByteArray) {
        val out = DataOutputStream(output)
        out.writeInt(payload.size)
        out.write(payload)
        out.flush()
    }

    /** Blocks until one full frame is read, or returns null if the stream ended/broke. */
    fun readFrame(input: InputStream): ByteArray? {
        val din = DataInputStream(input)
        val length = try {
            din.readInt()
        } catch (e: IOException) {
            return null
        }
        if (length <= 0 || length > MAX_FRAME_BYTES) return null
        val payload = ByteArray(length)
        return try {
            din.readFully(payload)
            payload
        } catch (e: IOException) {
            null
        }
    }
}
