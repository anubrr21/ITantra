package com.itantra.radio.time

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClockSyncEstimatorTest {

    @Test
    fun emptyEstimatorHasNoOffsetOrSamples() {
        val estimator = ClockSyncEstimator()
        assertNull(estimator.bestOffsetMs())
        assertNull(estimator.bestRttMs())
        assertEquals(0, estimator.sampleCount)
    }

    @Test
    fun computesExactOffsetForSymmetricNetworkDelay() {
        val estimator = ClockSyncEstimator()
        val trueOffset = 5_000L
        val oneWayDelay = 50L
        val originSentMs = 1_000L
        val remoteReplyMs = originSentMs + oneWayDelay + trueOffset
        val responseReceivedMs = originSentMs + 2 * oneWayDelay

        estimator.addSample(originSentMs, remoteReplyMs, responseReceivedMs)

        assertEquals(1, estimator.sampleCount)
        assertEquals(trueOffset, estimator.bestOffsetMs())
        assertEquals(2 * oneWayDelay, estimator.bestRttMs())
    }

    @Test
    fun negativeOffsetIsSupported() {
        val estimator = ClockSyncEstimator()
        estimator.addSample(originSentMs = 10_000L, remoteReplyMs = 9_500L, responseReceivedMs = 10_100L)
        assertEquals(-550L, estimator.bestOffsetMs())
    }

    @Test
    fun picksTheSampleWithTheLowestRttAsMostAccurate() {
        val estimator = ClockSyncEstimator()
        estimator.addSample(originSentMs = 1_000L, remoteReplyMs = 7_500L, responseReceivedMs = 3_000L)
        estimator.addSample(originSentMs = 2_000L, remoteReplyMs = 7_040L, responseReceivedMs = 2_080L)
        estimator.addSample(originSentMs = 3_000L, remoteReplyMs = 8_600L, responseReceivedMs = 5_000L)

        assertEquals(3, estimator.sampleCount)
        assertEquals(80L, estimator.bestRttMs())
        assertEquals(7_040L - 2_040L, estimator.bestOffsetMs())
    }

    @Test
    fun negativeRoundTripTimeSampleIsRejected() {
        val estimator = ClockSyncEstimator()
        estimator.addSample(originSentMs = 5_000L, remoteReplyMs = 5_000L, responseReceivedMs = 4_000L)
        assertEquals(0, estimator.sampleCount)
        assertNull(estimator.bestOffsetMs())
    }

    @Test
    fun zeroRoundTripTimeSampleIsAccepted() {
        val estimator = ClockSyncEstimator()
        estimator.addSample(originSentMs = 5_000L, remoteReplyMs = 5_100L, responseReceivedMs = 5_000L)
        assertEquals(1, estimator.sampleCount)
        assertEquals(0L, estimator.bestRttMs())
    }

    @Test
    fun resetClearsAllSamples() {
        val estimator = ClockSyncEstimator()
        estimator.addSample(1_000L, 6_000L, 1_100L)
        assertTrue(estimator.sampleCount > 0)
        estimator.reset()
        assertEquals(0, estimator.sampleCount)
        assertNull(estimator.bestOffsetMs())
    }
}
