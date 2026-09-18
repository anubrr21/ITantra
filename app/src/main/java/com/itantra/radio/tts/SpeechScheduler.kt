package com.itantra.radio.tts

import java.util.ArrayDeque
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class SpeechScheduler(
    private val splitter: (String) -> List<String>,
    private val synthesize: (String) -> ShortArray,
    private val playClip: (clip: ShortArray, isAlert: Boolean, shouldAbort: () -> Boolean) -> Boolean,
    private val onAlertStart: () -> Unit,
    private val onAlertEnd: () -> Unit,
) {
    private class Job(val sentences: ArrayDeque<String>, val isAlert: Boolean)

    private val lock = ReentrantLock()
    private val hasWork = lock.newCondition()
    private val alertQueue = ArrayDeque<Job>()
    private val normalQueue = ArrayDeque<Job>()
    private var running = true

    private val worker = Thread({ runLoop() }, "tts-scheduler").apply {
        isDaemon = true
        start()
    }

    fun enqueue(text: String, isAlert: Boolean) {
        val sentences = splitter(text)
        if (sentences.isEmpty()) return
        val job = Job(ArrayDeque(sentences), isAlert)
        lock.withLock {
            if (!running) return
            if (isAlert) alertQueue.addLast(job) else normalQueue.addLast(job)
            hasWork.signalAll()
        }
    }

    fun shutdown(): Boolean {
        lock.withLock {
            running = false
            alertQueue.clear()
            normalQueue.clear()
            hasWork.signalAll()
        }
        worker.join(SHUTDOWN_JOIN_MS)
        return !worker.isAlive
    }

    private fun isRunning(): Boolean = lock.withLock { running }

    private fun alertPending(): Boolean = lock.withLock { alertQueue.isNotEmpty() }

    private fun runLoop() {
        while (true) {
            val job = lock.withLock {
                while (running && alertQueue.isEmpty() && normalQueue.isEmpty()) hasWork.await()
                if (!running) return
                if (alertQueue.isNotEmpty()) alertQueue.removeFirst() else normalQueue.removeFirst()
            }
            runCatching { if (job.isAlert) playAlert(job) else playNormal(job) }
        }
    }

    private fun playAlert(job: Job) {
        onAlertStart()
        try {
            while (job.sentences.isNotEmpty() && isRunning()) {
                val clip = runCatching { synthesize(job.sentences.first) }.getOrNull()
                if (clip != null && clip.isNotEmpty()) {
                    playClip(clip, true) { !isRunning() }
                }
                job.sentences.removeFirst()
            }
        } finally {
            onAlertEnd()
        }
    }

    private fun playNormal(job: Job) {
        while (job.sentences.isNotEmpty() && isRunning()) {
            if (alertPending()) {
                requeue(job)
                return
            }
            val clip = runCatching { synthesize(job.sentences.first) }.getOrNull()
            if (clip == null || clip.isEmpty()) {
                job.sentences.removeFirst()
                continue
            }
            if (alertPending()) {
                requeue(job)
                return
            }
            val completed = playClip(clip, false) { alertPending() || !isRunning() }
            if (!completed) {
                if (isRunning()) requeue(job)
                return
            }
            job.sentences.removeFirst()
        }
    }

    private fun requeue(job: Job) {
        lock.withLock {
            if (running) normalQueue.addFirst(job)
        }
    }

    private companion object {
        const val SHUTDOWN_JOIN_MS = 3_000L
    }
}
