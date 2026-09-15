package com.itantra.radio.vad

import kotlin.math.sqrt

/** Detects whether a PCM16 frame contains speech. Used to find the pauses/stoppages the
 * problem statement asks the STT module to segment sentences on. */
interface VoiceActivityDetector {
    fun isSpeech(frame: ByteArray): Boolean
    fun reset()
}

/**
 * Bring-up implementation: short-term RMS energy against a slowly adapting noise floor.
 * Zero model weight, trivial CPU cost — good enough to prove the PTT/segmentation
 * pipeline end to end. Phase 2 replaces this with WebRTC VAD or Silero VAD, which handle
 * real-world noise (wind, crowds, disaster/field conditions) far more robustly; this
 * class exists purely so the rest of the app has a real interface to build against now.
 */
class EnergyVoiceActivityDetector(
    private val speechThresholdMultiplier: Double = 2.5,
    private val noiseFloorAdaptRate: Double = 0.05,
) : VoiceActivityDetector {

    private var noiseFloor = 0.0
    private var initialized = false

    override fun isSpeech(frame: ByteArray): Boolean {
        val energy = rms(frame)
        if (!initialized) {
            noiseFloor = energy
            initialized = true
            return false
        }
        val speech = energy > noiseFloor * speechThresholdMultiplier
        if (!speech) {
            noiseFloor = noiseFloor * (1 - noiseFloorAdaptRate) + energy * noiseFloorAdaptRate
        }
        return speech
    }

    override fun reset() {
        noiseFloor = 0.0
        initialized = false
    }

    private fun rms(frame: ByteArray): Double {
        if (frame.size < 2) return 0.0
        var sumSquares = 0.0
        var i = 0
        while (i + 1 < frame.size) {
            val sample = ((frame[i + 1].toInt() shl 8) or (frame[i].toInt() and 0xFF)).toShort()
            sumSquares += (sample * sample).toDouble()
            i += 2
        }
        return sqrt(sumSquares / (frame.size / 2))
    }
}
