package com.itantra.radio.tts

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import org.json.JSONObject
import java.nio.FloatBuffer
import java.nio.LongBuffer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

class IndicTtsEngine(
    context: Context,
    override val languageCode: String,
    private val speakerId: Long = SPEAKER_FEMALE,
) : TtsEngine {

    companion object {
        const val SPEAKER_FEMALE = 0L
        const val SPEAKER_MALE = 1L
        const val SAMPLE_RATE_HZ = 22_050
        private const val MEL_BINS = 80
        private const val PEAK_TARGET = 0.9f
        private const val TAIL_SILENCE_MS = 120
    }

    private val env = OrtEnvironment.getEnvironment()
    private val files = IndicTtsAssetProvisioner.ensureFiles(context, languageCode)

    private val fastpitchSession =
        env.createSession(files.getValue("fastpitch.onnx").absolutePath, OrtSession.SessionOptions())
    private val hifiganSession =
        env.createSession(files.getValue("hifigan.onnx").absolutePath, OrtSession.SessionOptions())

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

    fun synthesizeToPcm(text: String): ShortArray {
        val pieces = IndicTtsTextPrep.splitIntoSpeakableChunks(text).map { synthesizeChunk(it) }
        val merged = ShortArray(pieces.sumOf { it.size })
        var offset = 0
        for (piece in pieces) {
            piece.copyInto(merged, offset)
            offset += piece.size
        }
        return merged
    }

    private fun synthesizeChunk(chunk: String): ShortArray {
        val tokenIds = IndicTtsTextPrep.toTokenIds(chunk, charToId)
        if (tokenIds.isEmpty()) return ShortArray(0)
        val mel = runFastpitch(tokenIds)
        val waveform = runHifigan(mel)
        return toPcm16(waveform)
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

    private fun toPcm16(waveform: FloatArray): ShortArray {
        var peak = 0f
        for (sample in waveform) peak = max(peak, abs(sample))
        val gain = if (peak > 0f) PEAK_TARGET / peak else 1f
        val tail = SAMPLE_RATE_HZ * TAIL_SILENCE_MS / 1000
        val pcm = ShortArray(waveform.size + tail)
        for (i in waveform.indices) {
            pcm[i] = (waveform[i] * gain * Short.MAX_VALUE).roundToInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        return pcm
    }
}
