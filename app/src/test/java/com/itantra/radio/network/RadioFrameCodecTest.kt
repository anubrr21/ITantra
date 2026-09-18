package com.itantra.radio.network

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RadioFrameCodecTest {

    @Test
    fun normalTextRoundTripsAsNonAlert() {
        val decoded = RadioFrameCodec.decode(RadioFrameCodec.encode(RadioFrame.Text("मदद भेजो")))
        assertEquals(RadioFrame.Text("मदद भेजो", isAlert = false), decoded)
    }

    @Test
    fun alertTextRoundTripsAsAlert() {
        val decoded = RadioFrameCodec.decode(RadioFrameCodec.encode(RadioFrame.Text("मदद भेजो", isAlert = true)))
        assertEquals(RadioFrame.Text("मदद भेजो", isAlert = true), decoded)
    }

    @Test
    fun normalTextKeepsLegacyTagByte() {
        assertEquals(1.toByte(), RadioFrameCodec.encode(RadioFrame.Text("a"))[0])
    }

    @Test
    fun audioFramesAreUnchanged() {
        val pcm = byteArrayOf(1, 2, 3, 4)
        val encoded = RadioFrameCodec.encode(RadioFrame.Audio(pcm))
        assertEquals(0.toByte(), encoded[0])
        val decoded = RadioFrameCodec.decode(encoded)
        assertTrue(decoded is RadioFrame.Audio)
        assertArrayEquals(pcm, (decoded as RadioFrame.Audio).pcm)
    }

    @Test
    fun emptyAndUnknownFramesDecodeToNull() {
        assertNull(RadioFrameCodec.decode(ByteArray(0)))
        assertNull(RadioFrameCodec.decode(byteArrayOf(99, 1, 2)))
    }
}
