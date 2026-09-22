package com.itantra.radio.time

class ClockSyncEstimator {

    data class Sample(val rttMs: Long, val offsetMs: Long)

    private val samples = mutableListOf<Sample>()

    val sampleCount: Int
        get() = samples.size

    fun addSample(originSentMs: Long, remoteReplyMs: Long, responseReceivedMs: Long) {
        val rtt = responseReceivedMs - originSentMs
        if (rtt < 0) return
        val offset = remoteReplyMs - (originSentMs + responseReceivedMs) / 2
        samples.add(Sample(rtt, offset))
    }

    fun bestOffsetMs(): Long? = samples.minByOrNull { it.rttMs }?.offsetMs

    fun bestRttMs(): Long? = samples.minOfOrNull { it.rttMs }

    fun reset() {
        samples.clear()
    }
}
