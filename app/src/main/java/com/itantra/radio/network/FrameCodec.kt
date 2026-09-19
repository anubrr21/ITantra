package com.itantra.radio.network

import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer

object FrameCodec {
    private const val MAX_FRAME_BYTES = 1 shl 20
    private const val HEADER_BYTES = 4

    @Throws(IOException::class)
    fun writeFrame(output: OutputStream, payload: ByteArray) {
        val framed = ByteBuffer.allocate(HEADER_BYTES + payload.size).putInt(payload.size).put(payload).array()
        output.write(framed)
        output.flush()
    }

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
