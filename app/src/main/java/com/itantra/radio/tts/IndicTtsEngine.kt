package com.itantra.radio.tts

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.nio.FloatBuffer
import java.nio.LongBuffer

class IndicTtsEngine(
    context: Context,
    override val languageCode: String,
    private val speakerId: Long = SPEAKER_FEMALE,
    private val intraOpThreads: Int = DEFAULT_THREADS,
) : TtsEngine {

    companion object {
        const val SPEAKER_FEMALE = 0L
        const val SPEAKER_MALE = 1L
        const val SAMPLE_RATE_HZ = 22_050
        private const val MEL_BINS = 80
        private const val TAIL_SILENCE_MS = 120
        const val DEFAULT_THREADS = 2
        private const val LOG_TAG = "IndicTtsEngine"
    }

    private val env = OrtEnvironment.getEnvironment()
    private val files = IndicTtsAssetProvisioner.ensureFiles(context, languageCode)

    private fun sessionOptions() = OrtSession.SessionOptions().apply {
        if (intraOpThreads > 0) setIntraOpNumThreads(intraOpThreads)
    }

    private val fastpitchSession =
        env.createSession(files.getValue("fastpitch.onnx").absolutePath, sessionOptions())
    private val hifiganSession =
        env.createSession(files.getValue("hifigan.onnx").absolutePath, sessionOptions())

    private val charToId: Map<Char, Int> = run {
        val json = JSONObject(files.getValue("char_to_id.json").readText(Charsets.UTF_8))
        val map = HashMap<Char, Int>()
        for (key in json.keys()) {
            if (key.length == 1) map[key[0]] = json.getInt(key)
        }
        map
    }

    private val volumeGuard = AlertVolumeGuard(context)
    private val clipPlayer = ClipPlayer(SAMPLE_RATE_HZ)

    private val scheduler = SpeechScheduler(
        splitter = IndicTtsTextPrep::splitIntoSpeakableChunks,
        synthesize = ::synthesizeChunk,
        playClip = clipPlayer::play,
        onAlertStart = volumeGuard::raiseToMax,
        onAlertEnd = volumeGuard::restore,
    )

    override fun speak(text: String, isAlert: Boolean) {
        if (text.isBlank()) return
        scheduler.enqueue(text, isAlert)
    }

    override fun stop() {
        val drained = scheduler.shutdown()
        volumeGuard.restore()
        if (drained) {
            fastpitchSession.close()
            hifiganSession.close()
        }
    }

    fun synthesizeToPcm(text: String): ShortArray =
        Pcm16.concat(IndicTtsTextPrep.splitIntoSpeakableChunks(text).map { synthesizeChunk(it) })

    private fun synthesizeChunk(chunk: String): ShortArray {
        val tokenIds = IndicTtsTextPrep.toTokenIds(chunk, charToId)
        if (tokenIds.isEmpty()) return ShortArray(0)
        val t0 = System.nanoTime()
        val mel = runFastpitch(tokenIds)
        val t1 = System.nanoTime()
        val waveform = runHifigan(mel)
        val t2 = System.nanoTime()
        Log.d(LOG_TAG, "tokens=${tokenIds.size} frames=${mel.frames} fastpitch_ms=${(t1 - t0) / 1_000_000} hifigan_ms=${(t2 - t1) / 1_000_000}")
        return Pcm16.fromFloat(waveform, SAMPLE_RATE_HZ, TAIL_SILENCE_MS)
    }

    private class Mel(val channelsFirst: FloatArray, val frames: Int)

    @Suppress("UNCHECKED_CAST")
    private fun runFastpitch(tokenIds: LongArray): Mel {
        val tokens = OnnxTensor.createTensor(env, LongBuffer.wrap(tokenIds), longArrayOf(1, tokenIds.size.toLong()))
        val speaker = OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(speakerId)), longArrayOf(1))
        try {
            fastpitchSession.run(mapOf("token_ids" to tokens, "speaker_id" to speaker)).use { result ->
                val frames = (result[0].value as Array<Array<FloatArray>>)[0]
                val channelsFirst = FloatArray(MEL_BINS * frames.size)
                for (t in frames.indices) {
                    val row = frames[t]
                    for (bin in 0 until MEL_BINS) {
                        channelsFirst[bin * frames.size + t] = row[bin]
                    }
                }
                return Mel(channelsFirst, frames.size)
            }
        } finally {
            tokens.close()
            speaker.close()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun runHifigan(mel: Mel): FloatArray {
        val input = OnnxTensor.createTensor(
            env,
            FloatBuffer.wrap(mel.channelsFirst),
            longArrayOf(1, MEL_BINS.toLong(), mel.frames.toLong()),
        )
        try {
            hifiganSession.run(mapOf("mel" to input)).use { result ->
                return (result[0].value as Array<Array<FloatArray>>)[0][0]
            }
        } finally {
            input.close()
        }
    }
}
