package com.itantra.radio.stt

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.FloatBuffer
import java.nio.LongBuffer
import kotlin.math.exp
import kotlin.math.ln

class IndicConformerSttEngine(
    context: Context,
    override val languageCode: String,
) : SttEngine {

    companion object {
        private const val BLANK_ID = 256
        private const val WORD_BOUNDARY = "▁"
    }

    private val env = OrtEnvironment.getEnvironment()
    private val files = IndicConformerAssetProvisioner.ensureFiles(context)

    private val preprocessorSession =
        env.createSession(files.getValue("preprocessor.onnx").absolutePath, OrtSession.SessionOptions())
    private val encoderSession =
        env.createSession(files.getValue("encoder.onnx").absolutePath, OrtSession.SessionOptions())
    private val ctcDecoderSession =
        env.createSession(files.getValue("ctc_decoder.onnx").absolutePath, OrtSession.SessionOptions())

    private val vocab: List<String>
    private val languageMask: IntArray

    init {
        val vocabJson = JSONObject(files.getValue("vocab.json").readText(Charsets.UTF_8))
        val vocabArray = vocabJson.getJSONArray(languageCode)
        vocab = (0 until vocabArray.length()).map { vocabArray.getString(it) }

        val maskJson = JSONObject(files.getValue("language_masks.json").readText(Charsets.UTF_8))
        val maskArray = maskJson.getJSONArray(languageCode)
        languageMask = LanguageMask.selectedIndices(maskArray)
    }

    private val bufferLock = Any()
    private val sessionLock = Any()
    private val bufferedPcm = ByteArrayOutputStream()

    @Volatile
    private var onResult: ((String) -> Unit)? = null

    override fun start() {
        synchronized(bufferLock) { bufferedPcm.reset() }
    }

    override fun acceptAudioFrame(frame: ByteArray) {
        synchronized(bufferLock) { bufferedPcm.write(frame) }
    }

    override fun endUtterance() {
        val pcm = synchronized(bufferLock) {
            val snapshot = bufferedPcm.toByteArray()
            bufferedPcm.reset()
            snapshot
        }
        if (pcm.size < 2) return

        val samples = FloatArray(pcm.size / 2)
        for (i in samples.indices) {
            val lo = pcm[i * 2].toInt() and 0xFF
            val hi = pcm[i * 2 + 1].toInt()
            val sample = (hi shl 8) or lo
            samples[i] = sample.toShort() / 32768f
        }

        val text = synchronized(sessionLock) { runCatching { transcribe(samples) }.getOrDefault("") }
        if (text.isNotBlank()) onResult?.invoke(text)
    }

    override fun stop() {
        synchronized(bufferLock) { bufferedPcm.reset() }
        synchronized(sessionLock) {
            runCatching { preprocessorSession.close() }
            runCatching { encoderSession.close() }
            runCatching { ctcDecoderSession.close() }
        }
    }

    override fun setOnResult(callback: (String) -> Unit) {
        onResult = callback
    }

    private fun transcribe(samples: FloatArray): String {
        val (features, featuresLength) = runPreprocessor(samples)
        val (encoderOut, _) = runEncoder(features, featuresLength)
        val logits = runCtcDecoder(encoderOut)
        return decode(logits)
    }

    @Suppress("UNCHECKED_CAST")
    private fun runPreprocessor(samples: FloatArray): Pair<Array<Array<FloatArray>>, Long> {
        OnnxTensor.createTensor(env, FloatBuffer.wrap(samples), longArrayOf(1, samples.size.toLong())).use { inputSignal ->
            OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(samples.size.toLong())), longArrayOf(1)).use { length ->
                preprocessorSession.run(mapOf("input_signal" to inputSignal, "length" to length)).use { result ->
                    val features = result.get("features").get().value as Array<Array<FloatArray>>
                    val featuresLength = (result.get("features_length").get().value as LongArray)[0]
                    return features to featuresLength
                }
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun runEncoder(features: Array<Array<FloatArray>>, featuresLength: Long): Pair<Array<Array<FloatArray>>, Long> {
        OnnxTensor.createTensor(env, features).use { audioSignal ->
            OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(featuresLength)), longArrayOf(1)).use { length ->
                encoderSession.run(mapOf("audio_signal" to audioSignal, "length" to length)).use { result ->
                    val outputs = result.get("outputs").get().value as Array<Array<FloatArray>>
                    val encodedLengths = (result.get("encoded_lengths").get().value as LongArray)[0]
                    return outputs to encodedLengths
                }
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun runCtcDecoder(encoderOutput: Array<Array<FloatArray>>): Array<FloatArray> {
        OnnxTensor.createTensor(env, encoderOutput).use { input ->
            ctcDecoderSession.run(mapOf("encoder_output" to input)).use { result ->
                val logprobs = result.get("logprobs").get().value as Array<Array<FloatArray>>
                return logprobs[0]
            }
        }
    }

    private fun decode(logits: Array<FloatArray>): String {
        val collapsed = mutableListOf<Int>()
        var previous = -1
        for (frame in logits) {
            val masked = FloatArray(languageMask.size) { i -> frame[languageMask[i]] }
            val maxLogit = masked.max()
            var sumExp = 0.0
            for (v in masked) sumExp += exp((v - maxLogit).toDouble())
            val logSumExp = ln(sumExp)

            var bestIndex = 0
            var bestLogProb = Double.NEGATIVE_INFINITY
            for (i in masked.indices) {
                val logProb = (masked[i] - maxLogit) - logSumExp
                if (logProb > bestLogProb) {
                    bestLogProb = logProb
                    bestIndex = i
                }
            }
            if (bestIndex != previous) {
                collapsed.add(bestIndex)
                previous = bestIndex
            }
        }

        val sb = StringBuilder()
        for (index in collapsed) {
            if (index == BLANK_ID) continue
            sb.append(vocab.getOrElse(index) { "" })
        }
        return sb.toString().replace(WORD_BOUNDARY, " ").trim()
    }
}
