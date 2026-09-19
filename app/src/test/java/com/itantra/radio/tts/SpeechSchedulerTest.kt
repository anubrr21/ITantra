package com.itantra.radio.tts

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class SpeechSchedulerTest {

    private val texts = CopyOnWriteArrayList<String>()
    private val completed = CopyOnWriteArrayList<String>()
    private val alertStarts = AtomicInteger()
    private val alertEnds = AtomicInteger()
    private var scheduler: SpeechScheduler? = null

    @After
    fun tearDown() {
        scheduler?.shutdown()
    }

    private fun register(sentence: String): ShortArray = synchronized(texts) {
        texts.add(sentence)
        shortArrayOf((texts.size - 1).toShort())
    }

    private fun build(playClip: (String, Boolean, () -> Boolean) -> Boolean): SpeechScheduler {
        val created = SpeechScheduler(
            splitter = { listOf(it) },
            synthesize = { sentence -> register(sentence) },
            playClip = { clip, isAlert, shouldAbort ->
                val label = texts[clip[0].toInt()]
                val finished = playClip(label, isAlert, shouldAbort)
                if (finished) completed.add(label)
                finished
            },
            onAlertStart = { alertStarts.incrementAndGet() },
            onAlertEnd = { alertEnds.incrementAndGet() },
        )
        scheduler = created
        return created
    }

    private fun awaitTrue(timeoutMs: Long = 3_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            assertTrue("timed out waiting for condition", System.currentTimeMillis() < deadline)
            Thread.sleep(5)
        }
    }

    @Test
    fun normalMessagesPlayInArrivalOrder() {
        val s = build { _, _, _ -> true }
        s.enqueue("n1", false)
        s.enqueue("n2", false)
        s.enqueue("n3", false)
        awaitTrue { completed.size == 3 }
        assertEquals(listOf("n1", "n2", "n3"), completed.toList())
    }

    @Test
    fun alertInterruptsPlayingNormalThenNormalResumes() {
        val normalStarted = CountDownLatch(1)
        val firstNormalAttempt = AtomicInteger()
        val s = build { label, isAlert, shouldAbort ->
            if (!isAlert && label == "n1" && firstNormalAttempt.getAndIncrement() == 0) {
                normalStarted.countDown()
                while (!shouldAbort()) Thread.sleep(2)
                false
            } else {
                true
            }
        }
        s.enqueue("n1", false)
        assertTrue(normalStarted.await(2, TimeUnit.SECONDS))
        s.enqueue("a1", true)
        awaitTrue { completed.size == 2 }
        assertEquals(listOf("a1", "n1"), completed.toList())
    }

    @Test
    fun alertJumpsAheadOfQueuedNormalMessages() {
        val gate = CountDownLatch(1)
        val firstStarted = CountDownLatch(1)
        val s = build { label, _, _ ->
            if (label == "n1") {
                firstStarted.countDown()
                gate.await(2, TimeUnit.SECONDS)
            }
            true
        }
        s.enqueue("n1", false)
        assertTrue(firstStarted.await(2, TimeUnit.SECONDS))
        s.enqueue("n2", false)
        s.enqueue("a1", true)
        gate.countDown()
        awaitTrue { completed.size == 3 }
        assertEquals("n1", completed[0])
        assertTrue(completed.indexOf("a1") < completed.indexOf("n2"))
    }

    @Test
    fun alertIsNeverInterruptedByLaterAlertOrNormal() {
        val gate = CountDownLatch(1)
        val alertStarted = CountDownLatch(1)
        val abortObservedWhileAlertPlaying = AtomicInteger()
        val s = build { label, isAlert, shouldAbort ->
            if (label == "a1") {
                alertStarted.countDown()
                val deadline = System.currentTimeMillis() + 2_000
                while (gate.count > 0 && System.currentTimeMillis() < deadline) {
                    if (shouldAbort()) abortObservedWhileAlertPlaying.incrementAndGet()
                    Thread.sleep(2)
                }
            }
            true
        }
        s.enqueue("a1", true)
        assertTrue(alertStarted.await(2, TimeUnit.SECONDS))
        s.enqueue("a2", true)
        s.enqueue("n1", false)
        Thread.sleep(50)
        gate.countDown()
        awaitTrue { completed.size == 3 }
        assertEquals(listOf("a1", "a2", "n1"), completed.toList())
        assertEquals(0, abortObservedWhileAlertPlaying.get())
    }

    @Test
    fun alertStartAndEndCallbacksArePaired() {
        val s = build { _, _, _ -> true }
        s.enqueue("a1", true)
        s.enqueue("a2", true)
        awaitTrue { completed.size == 2 }
        awaitTrue { alertEnds.get() == 2 }
        assertEquals(2, alertStarts.get())
        assertEquals(2, alertEnds.get())
    }

    @Test
    fun synthesisFailureSkipsThatSentenceAndContinues() {
        val failing = SpeechScheduler(
            splitter = { it.split("|") },
            synthesize = { sentence ->
                if (sentence == "bad") error("boom")
                register(sentence)
            },
            playClip = { clip, _, _ ->
                completed.add(texts[clip[0].toInt()])
                true
            },
            onAlertStart = {},
            onAlertEnd = {},
        )
        scheduler = failing
        failing.enqueue("one|bad|two", false)
        awaitTrue { completed.size == 2 }
        assertEquals(listOf("one", "two"), completed.toList())
    }

    @Test
    fun shutdownDrainsWorkerAndIgnoresLaterMessages() {
        val s = build { _, _, _ -> true }
        assertTrue(s.shutdown())
        s.enqueue("late", false)
        Thread.sleep(50)
        assertTrue(completed.isEmpty())
    }

    @Test
    fun interruptedNormalMessageIsNotSynthesizedAgain() {
        val normalStarted = CountDownLatch(1)
        val firstAttempt = AtomicInteger()
        val s = build { label, isAlert, shouldAbort ->
            if (!isAlert && label == "n1" && firstAttempt.getAndIncrement() == 0) {
                normalStarted.countDown()
                while (!shouldAbort()) Thread.sleep(2)
                false
            } else {
                true
            }
        }
        s.enqueue("n1", false)
        assertTrue(normalStarted.await(2, TimeUnit.SECONDS))
        s.enqueue("a1", true)
        awaitTrue { completed.size == 2 }
        assertEquals(1, texts.count { it == "n1" })
    }

    @Test
    fun nextSentenceIsSynthesizedWhileCurrentOneIsPlaying() {
        val firstPlaying = CountDownLatch(1)
        val release = CountDownLatch(1)
        val s = SpeechScheduler(
            splitter = { it.split("|") },
            synthesize = { sentence -> register(sentence) },
            playClip = { clip, _, _ ->
                val label = texts[clip[0].toInt()]
                if (label == "s1") {
                    firstPlaying.countDown()
                    release.await(2, TimeUnit.SECONDS)
                }
                completed.add(label)
                true
            },
            onAlertStart = {},
            onAlertEnd = {},
        )
        scheduler = s
        s.enqueue("s1|s2|s3", false)
        assertTrue(firstPlaying.await(2, TimeUnit.SECONDS))
        awaitTrue { texts.contains("s2") }
        assertTrue("s3 must not be synthesized before s2 starts playing", !texts.contains("s3"))
        release.countDown()
        awaitTrue { completed.size == 3 }
        assertEquals(listOf("s1", "s2", "s3"), completed.toList())
        assertEquals(1, texts.count { it == "s2" })
    }
}
