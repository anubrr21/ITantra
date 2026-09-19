package com.itantra.radio.stt

import android.content.Context
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.vosk.Model
import java.io.File

@RunWith(AndroidJUnit4::class)
class VoskDeviceTest {

    private val tag = "VoskDeviceTest"
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
    }

    private fun loadModel(assetFolder: String): Model {
        var loaded: Model? = null
        var failure: Exception? = null
        VoskModelProvisioner.unpack(context, assetFolder, { loaded = it }, { failure = it })
        assertTrue("model $assetFolder failed to load: $failure", loaded != null)
        return loaded!!
    }

    private fun readWavPcm(file: File): ByteArray {
        val bytes = file.readBytes()
        return bytes.copyOfRange(44, bytes.size)
    }

    private fun transcribe(model: Model, language: String, pcm: ByteArray): String {
        val engine = VoskSttEngine(model, language)
        var heard = ""
        engine.setOnResult { heard = it }
        engine.start()
        var offset = 0
        while (offset < pcm.size) {
            val end = minOf(offset + 640, pcm.size)
            engine.acceptAudioFrame(pcm.copyOfRange(offset, end))
            offset = end
        }
        engine.endUtterance()
        engine.stop()
        return heard
    }

    private fun sample(name: String): File = File(context.getExternalFilesDir(null), name)

    @Test
    fun englishModelLoadsAndTranscribesRealSpeech() {
        val start = System.currentTimeMillis()
        val model = loadModel("model-en-us")
        Log.i(tag, "EN_LOAD_MS=${System.currentTimeMillis() - start}")
        val cases = mapOf("en_001.wav" to "send help immediately", "en_002.wav" to "we are trapped near the bridge")
        for ((file, reference) in cases) {
            val wav = sample(file)
            assertTrue("missing ${wav.path}", wav.exists())
            val heard = transcribe(model, "en", readWavPcm(wav))
            Log.i(tag, "EN file=$file reference=\"$reference\" heard=\"$heard\"")
            assertTrue("no text recognised for $file", heard.isNotBlank())
        }
    }

    @Test
    fun hindiModelLoadsAndTranscribesRealSpeech() {
        val start = System.currentTimeMillis()
        val model = loadModel("model-hi")
        Log.i(tag, "HI_LOAD_MS=${System.currentTimeMillis() - start}")
        for (file in listOf("hi_001.wav", "hi_002.wav", "hi_003.wav")) {
            val wav = sample(file)
            assertTrue("missing ${wav.path}", wav.exists())
            val heard = transcribe(model, "hi", readWavPcm(wav))
            Log.i(tag, "HI file=$file heard=\"$heard\"")
            assertTrue("no text recognised for $file", heard.isNotBlank())
        }
    }
}
