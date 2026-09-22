package com.itantra.radio.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PendingTimestampsTest {

    @Test
    fun pollOnEmptyQueueReturnsNull() {
        val queue = PendingTimestamps()
        assertNull(queue.pollMatching(1_000L))
    }

    @Test
    fun pollReturnsValuesInFifoOrder() {
        val queue = PendingTimestamps()
        queue.push(100L)
        queue.push(200L)
        assertEquals(100L, queue.pollMatching(300L))
        assertEquals(200L, queue.pollMatching(300L))
        assertNull(queue.pollMatching(300L))
    }

    @Test
    fun oldestEntryIsDroppedWhenMaxSizeIsExceeded() {
        val queue = PendingTimestamps(maxSize = 2)
        queue.push(1L)
        queue.push(2L)
        queue.push(3L)
        assertEquals(2L, queue.pollMatching(1_000L))
        assertEquals(3L, queue.pollMatching(1_000L))
    }

    @Test
    fun entriesOlderThanMaxAgeAreSkippedAndDiscarded() {
        val queue = PendingTimestamps(maxAgeMs = 500L)
        queue.push(1_000L)
        queue.push(1_600L)
        assertEquals(1_600L, queue.pollMatching(2_000L))
    }

    @Test
    fun allStaleEntriesLeaveTheQueueEmpty() {
        val queue = PendingTimestamps(maxAgeMs = 500L)
        queue.push(1_000L)
        queue.push(1_100L)
        assertNull(queue.pollMatching(5_000L))
        assertNull(queue.pollMatching(5_000L))
    }
}
