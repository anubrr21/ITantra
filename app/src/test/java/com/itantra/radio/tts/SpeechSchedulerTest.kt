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

    private fun build(playClip: (String, Boolean, () -> Boolean) -> Boolean): SpeechScheduler {
        val created = SpeechScheduler(
            splitter = { listOf(it) },
            synthesize = { sentence ->
                texts.add(sentence)
                shortArrayOf((texts.size - 1).toShort())
            },
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
                texts.add(sentence)
                shortArrayOf((texts.size - 1).toShort())
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
}
