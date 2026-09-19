package com.itantra.radio.tts

import java.util.ArrayDeque
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class SpeechScheduler(
    private val splitter: (String) -> List<String>,
    private val synthesize: (String) -> ShortArray,
    private val playClip: (clip: ShortArray, isAlert: Boolean, shouldAbort: () -> Boolean) -> Boolean,
    private val onAlertStart: () -> Unit,
    private val onAlertEnd: () -> Unit,
) {
    private class Job(val sentences: ArrayDeque<String>, val isAlert: Boolean) {
        var headClip: ShortArray? = null
        var prefetch: Future<ShortArray?>? = null
    }

    private val lock = ReentrantLock()
    private val hasWork = lock.newCondition()
    private val alertQueue = ArrayDeque<Job>()
    private val normalQueue = ArrayDeque<Job>()
    private var running = true

    private val prefetchExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "tts-prefetch").apply { isDaemon = true }
    }

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
        prefetchExecutor.shutdownNow()
        worker.join(SHUTDOWN_JOIN_MS)
        val prefetchDone = prefetchExecutor.awaitTermination(SHUTDOWN_JOIN_MS, TimeUnit.MILLISECONDS)
        return !worker.isAlive && prefetchDone
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

    private fun safeSynthesize(sentence: String): ShortArray? = runCatching { synthesize(sentence) }.getOrNull()

    private fun obtainHeadClip(job: Job): ShortArray? {
        job.headClip?.let { return it }
        val pending = job.prefetch
        job.prefetch = null
        val clip = if (pending != null) {
            runCatching { pending.get() }.getOrNull()
        } else {
            safeSynthesize(job.sentences.first)
        }
        job.headClip = clip
        return clip
    }

    private fun startPrefetch(job: Job) {
        if (job.prefetch != null || job.sentences.size < 2 || !isRunning()) return
        val next = job.sentences.elementAt(1)
        job.prefetch = runCatching { prefetchExecutor.submit<ShortArray?> { safeSynthesize(next) } }.getOrNull()
    }

    private fun finishHead(job: Job) {
        job.headClip = null
        job.sentences.removeFirst()
    }

    private fun playAlert(job: Job) {
        onAlertStart()
        try {
            while (job.sentences.isNotEmpty() && isRunning()) {
                val clip = obtainHeadClip(job)
                if (clip != null && clip.isNotEmpty()) {
                    startPrefetch(job)
                    playClip(clip, true) { !isRunning() }
                }
                finishHead(job)
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
            val clip = obtainHeadClip(job)
            if (clip == null || clip.isEmpty()) {
                finishHead(job)
                continue
            }
            if (alertPending()) {
                requeue(job)
                return
            }
            startPrefetch(job)
            val completed = playClip(clip, false) { alertPending() || !isRunning() }
            if (!completed) {
                if (isRunning()) requeue(job)
                return
            }
            finishHead(job)
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
