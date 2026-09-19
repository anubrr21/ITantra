package com.itantra.radio.tts

import android.content.Context
import android.os.Debug
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.itantra.radio.stt.IndicConformerSttEngine
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SpeechLoopDeviceTest {

    private val tag = "SpeechLoopTest"
    private lateinit var context: Context
    private var tts: PiperTtsEngine? = null
    private var stt: IndicConformerSttEngine? = null

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
    }

    @After
    fun tearDown() {
        tts?.stop()
        stt?.stop()
    }

    private fun resampleTo16k(pcm: ShortArray, sourceRate: Int): ShortArray {
        val outLength = (pcm.size.toLong() * 16_000 / sourceRate).toInt()
        val out = ShortArray(outLength)
        for (i in out.indices) {
            val position = i.toDouble() * sourceRate / 16_000
            val index = position.toInt().coerceAtMost(pcm.size - 2)
            val fraction = position - index
            out[i] = (pcm[index] * (1 - fraction) + pcm[index + 1] * fraction).toInt().toShort()
        }
        return out
    }

    private fun toBytes(pcm: ShortArray): ByteArray {
        val bytes = ByteArray(pcm.size * 2)
        for (i in pcm.indices) {
            bytes[i * 2] = (pcm[i].toInt() and 0xFF).toByte()
            bytes[i * 2 + 1] = ((pcm[i].toInt() shr 8) and 0xFF).toByte()
        }
        return bytes
    }

    @Test
    fun piperSpeechIsUnderstoodByIndicConformerWithBothModelsLoaded() {
        val loadStart = System.currentTimeMillis()
        val recognizer = IndicConformerSttEngine(context, "hi")
        stt = recognizer
        val sttLoadMs = System.currentTimeMillis() - loadStart
        val voiceStart = System.currentTimeMillis()
        val voice = PiperTtsEngine(context, "hi")
        tts = voice
        val ttsLoadMs = System.currentTimeMillis() - voiceStart
        val nativeMb = Debug.getNativeHeapAllocatedSize() / 1_000_000
        Log.i(tag, "STT_LOAD_MS=$sttLoadMs TTS_LOAD_MS=$ttsLoadMs native_heap_mb=$nativeMb")

        val cases = listOf(
            "मदद भेजो अभी" to "मदद",
            "पानी खत्म हो गया है जल्दी आओ" to "पानी",
        )
        for ((text, mustContain) in cases) {
            var heard = ""
            recognizer.setOnResult { heard = it }
            recognizer.start()

            val pcm16k = resampleTo16k(voice.synthesizeToPcm(text), voice.sampleRateHz)
            val bytes = toBytes(pcm16k)
            var offset = 0
            while (offset < bytes.size) {
                val end = minOf(offset + 640, bytes.size)
                recognizer.acceptAudioFrame(bytes.copyOfRange(offset, end))
                offset = end
            }
            val sttStart = System.currentTimeMillis()
            recognizer.endUtterance()
            val sttMs = System.currentTimeMillis() - sttStart
            Log.i(tag, "SPOKEN=$text HEARD=$heard stt_ms=$sttMs")
            assertTrue("expected \"$mustContain\" in \"$heard\"", heard.contains(mustContain))
        }
    }
}
