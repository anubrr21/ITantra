package com.itantra.radio.vad

import com.konovalov.vad.webrtc.VadWebRTC
import com.konovalov.vad.webrtc.config.FrameSize
import com.konovalov.vad.webrtc.config.Mode
import com.konovalov.vad.webrtc.config.SampleRate
import kotlin.math.sqrt

interface VoiceActivityDetector {
    fun isSpeech(frame: ByteArray): Boolean
    fun reset()
}

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

class WebRtcVoiceActivityDetector : VoiceActivityDetector, AutoCloseable {
    private val vad = VadWebRTC(
        sampleRate = SampleRate.SAMPLE_RATE_16K,
        frameSize = FrameSize.FRAME_SIZE_320,
        mode = Mode.VERY_AGGRESSIVE,
        silenceDurationMs = 300,
        speechDurationMs = 50,
    )

    override fun isSpeech(frame: ByteArray): Boolean = vad.isSpeech(frame)

    override fun reset() {}

    override fun close() {
        vad.close()
    }
}
