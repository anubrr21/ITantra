package com.itantra.radio.tts

import android.content.Context
import android.media.AudioManager
import android.os.Debug
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

@RunWith(AndroidJUnit4::class)
class IndicTtsEngineDeviceTest {

    private val tag = "IndicTtsDeviceTest"
    private lateinit var context: Context
    private var engine: IndicTtsEngine? = null

    private val sentences = listOf(
        "नमस्ते, यह एक परीक्षण है",
        "मदद भेजो अभी",
        "पानी खत्म हो गया है जल्दी आओ हमें बहुत मदद चाहिए यहाँ बाढ़ आ गई है और सड़क बंद है",
    )

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
    }

    @After
    fun tearDown() {
        engine?.stop()
    }

    private fun outputDir(): File = context.getExternalFilesDir(null)!!.also { it.mkdirs() }

    private fun rms(pcm: ShortArray): Double {
        var sum = 0.0
        for (s in pcm) sum += s.toDouble() * s.toDouble()
        return sqrt(sum / pcm.size) / Short.MAX_VALUE
    }

    private fun writeWav(file: File, pcm: ShortArray, sampleRate: Int) {
        val dataBytes = pcm.size * 2
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + dataBytes); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1)
            putInt(sampleRate); putInt(sampleRate * 2); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(dataBytes)
        }
        RandomAccessFile(file, "rw").use { raf ->
            raf.setLength(0)
            raf.write(header.array())
            val body = ByteBuffer.allocate(dataBytes).order(ByteOrder.LITTLE_ENDIAN)
            pcm.forEach { body.putShort(it) }
            raf.write(body.array())
        }
    }

    @Test
    fun synthesizesAudibleSpeechAndReportsPerformance() {
        val loadStart = System.currentTimeMillis()
        val tts = IndicTtsEngine(context, "hi")
        engine = tts
        val loadMs = System.currentTimeMillis() - loadStart
        Log.i(tag, "LOAD_MS=$loadMs native_heap_mb=${Debug.getNativeHeapAllocatedSize() / 1_000_000}")

        sentences.forEachIndexed { index, text ->
            val start = System.currentTimeMillis()
            val pcm = tts.synthesizeToPcm(text)
            val elapsedMs = System.currentTimeMillis() - start
            val audioMs = pcm.size * 1000L / IndicTtsEngine.SAMPLE_RATE_HZ
            val level = rms(pcm)
            Log.i(
                tag,
                "SENTENCE=$index synth_ms=$elapsedMs audio_ms=$audioMs rtf=${"%.2f".format(elapsedMs.toDouble() / audioMs)} rms=${"%.4f".format(level)}",
            )
            assertTrue("sentence $index produced too little audio: ${audioMs}ms", audioMs > 500)
            assertTrue("sentence $index is near-silent: rms=$level", level > 0.02)
            writeWav(File(outputDir(), "kotlin_hi_$index.wav"), pcm, IndicTtsEngine.SAMPLE_RATE_HZ)
        }
    }

    @Test
    fun maleAndFemaleVoicesDiffer() {
        val female = IndicTtsEngine(context, "hi", IndicTtsEngine.SPEAKER_FEMALE)
        val femalePcm = female.synthesizeToPcm(sentences[0])
        female.stop()
        val male = IndicTtsEngine(context, "hi", IndicTtsEngine.SPEAKER_MALE)
        engine = male
        val malePcm = male.synthesizeToPcm(sentences[0])
        writeWav(File(outputDir(), "kotlin_hi_female.wav"), femalePcm, IndicTtsEngine.SAMPLE_RATE_HZ)
        writeWav(File(outputDir(), "kotlin_hi_male.wav"), malePcm, IndicTtsEngine.SAMPLE_RATE_HZ)
        assertTrue(femalePcm.size != malePcm.size || !femalePcm.contentEquals(malePcm))
    }

    @Test
    fun alertRaisesAlarmVolumeToMaxThenRestoresIt() {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val original = audioManager.getStreamVolume(AudioManager.STREAM_ALARM)
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
        val tts = IndicTtsEngine(context, "hi")
        engine = tts

        tts.speak(sentences[1], isAlert = true)

        var sawMax = false
        val raiseDeadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < raiseDeadline && !sawMax) {
            sawMax = audioManager.getStreamVolume(AudioManager.STREAM_ALARM) == max
            Thread.sleep(20)
        }
        assertTrue("alarm volume never reached max ($max)", sawMax)

        val restoreDeadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < restoreDeadline &&
            audioManager.getStreamVolume(AudioManager.STREAM_ALARM) != original
        ) {
            Thread.sleep(50)
        }
        assertEquals("alarm volume was not restored", original, audioManager.getStreamVolume(AudioManager.STREAM_ALARM))
    }

    @Test
    fun normalThenAlertPlaybackCompletesWithoutCrashing() {
        val tts = IndicTtsEngine(context, "hi")
        engine = tts
        tts.speak(sentences[2], isAlert = false)
        Thread.sleep(1_500)
        tts.speak(sentences[1], isAlert = true)
        Thread.sleep(20_000)
        tts.stop()
        engine = null
    }
}
