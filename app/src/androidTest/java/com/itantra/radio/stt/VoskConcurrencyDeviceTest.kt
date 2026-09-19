package com.itantra.radio.stt

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.concurrent.thread

@RunWith(AndroidJUnit4::class)
class VoskConcurrencyDeviceTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun pcm(name: String): ByteArray {
        val bytes = File(context.getExternalFilesDir(null), name).readBytes()
        return bytes.copyOfRange(44, bytes.size)
    }

    @Test
    fun feedingAudioWhileFinishingUtterancesDoesNotCrash() {
        var model: org.vosk.Model? = null
        VoskModelProvisioner.unpack(context, "model-en-us", { model = it }, { throw it })
        val engine = VoskSttEngine(model!!, "en")
        engine.setOnResult { }
        engine.start()

        val audio = pcm("en_001.wav")
        var running = true
        val feeder = thread {
            while (running) {
                var offset = 0
                while (offset < audio.size && running) {
                    val end = minOf(offset + 640, audio.size)
                    engine.acceptAudioFrame(audio.copyOfRange(offset, end))
                    offset = end
                }
            }
        }

        val deadline = System.currentTimeMillis() + 6_000
        var finishes = 0
        while (System.currentTimeMillis() < deadline) {
            engine.endUtterance()
            finishes++
            Thread.sleep(5)
        }
        running = false
        feeder.join()
        engine.stop()
        assertTrue("only $finishes finishes", finishes >= 5)
    }
}
