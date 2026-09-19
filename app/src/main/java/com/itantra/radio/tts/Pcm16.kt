package com.itantra.radio.tts

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

object Pcm16 {
    private const val PEAK_TARGET = 0.9f

    fun fromFloat(waveform: FloatArray, sampleRateHz: Int, tailSilenceMs: Int): ShortArray {
        var peak = 0f
        for (sample in waveform) peak = max(peak, abs(sample))
        val gain = if (peak > 0f) PEAK_TARGET / peak else 1f
        val tail = sampleRateHz * tailSilenceMs / 1000
        val pcm = ShortArray(waveform.size + tail)
        for (i in waveform.indices) {
            pcm[i] = (waveform[i] * gain * Short.MAX_VALUE).roundToInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        return pcm
    }

    fun concat(pieces: List<ShortArray>): ShortArray {
        val merged = ShortArray(pieces.sumOf { it.size })
        var offset = 0
        for (piece in pieces) {
            piece.copyInto(merged, offset)
            offset += piece.size
        }
        return merged
    }
}
