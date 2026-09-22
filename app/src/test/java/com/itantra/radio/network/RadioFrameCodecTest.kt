package com.itantra.radio.network

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RadioFrameCodecTest {

    @Test
    fun normalTextRoundTripsAsNonAlertWithoutLanguage() {
        val decoded = RadioFrameCodec.decode(RadioFrameCodec.encode(RadioFrame.Text("मदद भेजो")))
        assertEquals(RadioFrame.Text("मदद भेजो", isAlert = false, languageCode = null), decoded)
    }

    @Test
    fun alertTextRoundTripsAsAlert() {
        val decoded = RadioFrameCodec.decode(RadioFrameCodec.encode(RadioFrame.Text("मदद भेजो", isAlert = true)))
        assertEquals(RadioFrame.Text("मदद भेजो", isAlert = true, languageCode = null), decoded)
    }

    @Test
    fun textWithLanguageRoundTrips() {
        val frame = RadioFrame.Text("we need water", isAlert = false, languageCode = "en")
        assertEquals(frame, RadioFrameCodec.decode(RadioFrameCodec.encode(frame)))
    }

    @Test
    fun alertWithLanguageRoundTripsIncludingDevanagari() {
        val frame = RadioFrame.Text("मदद भेजो अभी", isAlert = true, languageCode = "hi")
        assertEquals(frame, RadioFrameCodec.decode(RadioFrameCodec.encode(frame)))
    }

    @Test
    fun textContainingZeroByteLikeCharactersStillKeepsLanguageSeparate() {
        val frame = RadioFrame.Text("a b", isAlert = false, languageCode = "en")
        val decoded = RadioFrameCodec.decode(RadioFrameCodec.encode(frame)) as RadioFrame.Text
        assertEquals("a b", decoded.text)
        assertEquals("en", decoded.languageCode)
    }

    @Test
    fun legacyTagBytesAreUnchanged() {
        assertEquals(1.toByte(), RadioFrameCodec.encode(RadioFrame.Text("a"))[0])
        assertEquals(2.toByte(), RadioFrameCodec.encode(RadioFrame.Text("a", isAlert = true))[0])
        assertEquals(3.toByte(), RadioFrameCodec.encode(RadioFrame.Text("a", languageCode = "en"))[0])
        assertEquals(4.toByte(), RadioFrameCodec.encode(RadioFrame.Text("a", isAlert = true, languageCode = "en"))[0])
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
    fun emptyUnknownAndMalformedFramesDecodeToNull() {
        assertNull(RadioFrameCodec.decode(ByteArray(0)))
        assertNull(RadioFrameCodec.decode(byteArrayOf(99, 1, 2)))
        assertNull(RadioFrameCodec.decode(byteArrayOf(3, 'e'.code.toByte(), 'n'.code.toByte())))
    }

    @Test
    fun peerSpeakingSignalsRoundTrip() {
        assertEquals(RadioFrame.PeerSpeaking(true), RadioFrameCodec.decode(RadioFrameCodec.encode(RadioFrame.PeerSpeaking(true))))
        assertEquals(RadioFrame.PeerSpeaking(false), RadioFrameCodec.decode(RadioFrameCodec.encode(RadioFrame.PeerSpeaking(false))))
        assertEquals(5.toByte(), RadioFrameCodec.encode(RadioFrame.PeerSpeaking(true))[0])
        assertEquals(6.toByte(), RadioFrameCodec.encode(RadioFrame.PeerSpeaking(false))[0])
    }
}

