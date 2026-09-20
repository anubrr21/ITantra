package com.itantra.radio.stt

import android.content.Context
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class WhisperDeviceTest {

    private val tag = "WhisperDeviceTest"
    private lateinit var context: Context
    private var engine: WhisperSttEngine? = null

    private val references = mapOf(
        "en_001.wav" to "send help immediately",
        "en_002.wav" to "we are trapped near the bridge",
        "en_003.wav" to "what is your name",
        "en_004.wav" to "we need water and food",
        "en_005.wav" to "the road is blocked by a fallen tree",
        "en_006.wav" to "there are three injured people here",
        "en_007.wav" to "please send a doctor to the school",
        "en_008.wav" to "my phone battery is almost finished",
        "en_009.wav" to "the flood water is rising fast",
        "en_010.wav" to "call the rescue team immediately",
    )

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
    }

    @After
    fun tearDown() {
        engine?.stop()
    }

    private fun samples(file: File): FloatArray {
        val bytes = file.readBytes()
        val out = FloatArray((bytes.size - 44) / 2)
        for (i in out.indices) {
            val lo = bytes[44 + i * 2].toInt() and 0xFF
            val hi = bytes[44 + i * 2 + 1].toInt()
            out[i] = ((hi shl 8) or lo).toShort() / 32768f
        }
        return out
    }

    private fun words(text: String): List<String> =
        text.lowercase().filter { it.isLetterOrDigit() || it == ' ' || it == '\'' }.split(" ").filter { it.isNotEmpty() }

    private fun editDistance(a: List<String>, b: List<String>): Int {
        val d = Array(a.size + 1) { IntArray(b.size + 1) }
        for (i in 0..a.size) d[i][0] = i
        for (j in 0..b.size) d[0][j] = j
        for (i in 1..a.size) for (j in 1..b.size) {
            d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
        }
        return d[a.size][b.size]
    }

    @Test
    fun whisperLoadsAndTranscribesRealEnglishSpeech() {
        val loadStart = System.currentTimeMillis()
        val whisper = WhisperSttEngine(context, "en")
        engine = whisper
        Log.i(tag, "LOAD_MS=${System.currentTimeMillis() - loadStart}")

        var errors = 0
        var totalWords = 0
        var totalMs = 0L
        for ((name, reference) in references) {
            val wav = File(context.getExternalFilesDir(null), name)
            assertTrue("missing ${wav.path}", wav.exists())
            val start = System.currentTimeMillis()
            val heard = whisper.transcribe(samples(wav))
            val elapsed = System.currentTimeMillis() - start
            totalMs += elapsed
            val ref = words(reference)
            errors += editDistance(ref, words(heard))
            totalWords += ref.size
            Log.i(tag, "FILE=$name decode_ms=$elapsed reference=\"$reference\" heard=\"$heard\"")
        }
        val wer = errors * 100.0 / totalWords
        Log.i(tag, "WER=${"%.1f".format(wer)}% avg_decode_ms=${totalMs / references.size}")
        assertTrue("WER too high: $wer", wer < 30.0)
    }
}
