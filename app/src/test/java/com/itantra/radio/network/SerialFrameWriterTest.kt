package com.itantra.radio.network

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import kotlin.concurrent.thread

class SerialFrameWriterTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun payload(sender: Int, sequence: Int): ByteArray =
        ByteArray(40) { index -> if (index == 0) sender.toByte() else if (index == 1) sequence.toByte() else (sender + sequence + index).toByte() }

    @Test
    fun framesFromManyThreadsArriveIntactAndInOrderPerSender() {
        val sink = ByteArrayOutputStream()
        val writer = SerialFrameWriter(scope, sink) { throw it }
        val senders = 4
        val perSender = 50

        val threads = (0 until senders).map { sender ->
            thread {
                for (sequence in 0 until perSender) writer.send(payload(sender, sequence))
            }
        }
        threads.forEach { it.join() }

        val deadline = System.currentTimeMillis() + 3_000
        while (System.currentTimeMillis() < deadline && sink.size() < senders * perSender * 44) Thread.sleep(5)
        writer.close()

        val input = ByteArrayInputStream(sink.toByteArray())
        val lastSequence = IntArray(senders) { -1 }
        var count = 0
        while (true) {
            val frame = FrameCodec.readFrame(input) ?: break
            val sender = frame[0].toInt()
            val sequence = frame[1].toInt()
            assertArrayEquals(payload(sender, sequence), frame)
            assertTrue("sender $sender out of order", sequence > lastSequence[sender])
            lastSequence[sender] = sequence
            count++
        }
        assertEquals(senders * perSender, count)
    }

    @Test
    fun writeFrameProducesOneWriteWithHeaderAndPayload() {
        var writes = 0
        val sink = ByteArrayOutputStream()
        val counting = object : OutputStream() {
            override fun write(b: Int) {
                writes++
                sink.write(b)
            }

            override fun write(b: ByteArray, off: Int, len: Int) {
                writes++
                sink.write(b, off, len)
            }
        }
        FrameCodec.writeFrame(counting, byteArrayOf(1, 2, 3))
        assertEquals(1, writes)
        assertArrayEquals(byteArrayOf(0, 0, 0, 3, 1, 2, 3), sink.toByteArray())
    }

    @Test
    fun readFrameRejectsGarbageLengthAndTruncatedPayload() {
        assertNull(FrameCodec.readFrame(ByteArrayInputStream(byteArrayOf(-1, -1, -1, -1))))
        assertNull(FrameCodec.readFrame(ByteArrayInputStream(byteArrayOf(0, 0, 0, 9, 1, 2))))
    }

    @Test
    fun writeFailureIsReportedOnce() {
        val failures = java.util.concurrent.atomic.AtomicInteger()
        val broken = object : OutputStream() {
            override fun write(b: Int) = throw IOException("broken pipe")
        }
        val writer = SerialFrameWriter(scope, broken) { failures.incrementAndGet() }
        writer.send(byteArrayOf(1))
        writer.send(byteArrayOf(2))
        val deadline = System.currentTimeMillis() + 2_000
        while (failures.get() == 0 && System.currentTimeMillis() < deadline) Thread.sleep(5)
        Thread.sleep(50)
        assertEquals(1, failures.get())
    }
}
