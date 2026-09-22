package com.itantra.radio.util

import java.util.concurrent.ConcurrentLinkedDeque

class PendingTimestamps(
    private val maxAgeMs: Long = DEFAULT_MAX_AGE_MS,
    private val maxSize: Int = DEFAULT_MAX_SIZE,
) {
    private val queue = ConcurrentLinkedDeque<Long>()

    fun push(timestampMs: Long) {
        queue.addLast(timestampMs)
        while (queue.size > maxSize) queue.pollFirst()
    }

    fun pollMatching(nowMs: Long): Long? {
        while (true) {
            val head = queue.pollFirst() ?: return null
            if (nowMs - head <= maxAgeMs) return head
        }
    }

    companion object {
        const val DEFAULT_MAX_AGE_MS = 15_000L
        const val DEFAULT_MAX_SIZE = 4
    }
}
