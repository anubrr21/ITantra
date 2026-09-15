package com.itantra.radio.network

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

object FrameCodec {
    private const val MAX_FRAME_BYTES = 1 shl 20

    @Throws(IOException::class)
    fun writeFrame(output: OutputStream, payload: ByteArray) {
        val out = DataOutputStream(output)
        out.writeInt(payload.size)
        out.write(payload)
        out.flush()
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
