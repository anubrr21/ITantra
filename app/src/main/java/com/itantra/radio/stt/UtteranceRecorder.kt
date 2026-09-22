package com.itantra.radio.stt

import android.content.Context
import android.content.pm.ApplicationInfo
import com.itantra.radio.audio.AudioConfig
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

class UtteranceRecorder(context: Context, private val engineName: String) {
    private val enabled = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    private val directory = File(context.getExternalFilesDir(null), "utterances")

    fun save(pcm: ByteArray) {
        if (!enabled || pcm.isEmpty()) return
        runCatching {
            directory.mkdirs()
            prune()
            val file = File(directory, "utt_${System.currentTimeMillis()}_$engineName.wav")
            writeWav(file, pcm)
        }
    }

    private fun prune() {
        val files = directory.listFiles()?.sortedBy { it.name } ?: return
        if (files.size >= KEEP_LAST) files.take(files.size - KEEP_LAST + 1).forEach { it.delete() }
    }

    private fun writeWav(file: File, pcm: ByteArray) {
        val rate = AudioConfig.SAMPLE_RATE_HZ
        val header = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(HEADER_BYTES - 8 + pcm.size); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1)
            putInt(rate); putInt(rate * 2); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(pcm.size)
        }
        RandomAccessFile(file, "rw").use { raf ->
            raf.setLength(0)
            raf.write(header.array())
            raf.write(pcm)
        }
    }

    private companion object {
        const val KEEP_LAST = 40
        const val HEADER_BYTES = 44
    }
}
