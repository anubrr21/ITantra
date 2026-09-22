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

    @Test
    fun textWithTimingRoundTrips() {
        val frame = RadioFrame.Text(
            text = "we need water and food",
            isAlert = false,
            languageCode = "en",
            speechEndEpochMs = 1_726_000_000_123L,
            sentEpochMs = 1_726_000_000_456L,
        )
        assertEquals(frame, RadioFrameCodec.decode(RadioFrameCodec.encode(frame)))
    }

    @Test
    fun alertTextWithTimingRoundTripsIncludingDevanagari() {
        val frame = RadioFrame.Text(
            text = "मदद भेजो अभी",
            isAlert = true,
            languageCode = "hi",
            speechEndEpochMs = 42L,
            sentEpochMs = 999_999_999_999L,
        )
        assertEquals(frame, RadioFrameCodec.decode(RadioFrameCodec.encode(frame)))
    }

    @Test
    fun timingFrameUsesItsOwnTagBytesDistinctFromLanguageOnlyFrames() {
        val withTiming = RadioFrame.Text("hi", languageCode = "en", speechEndEpochMs = 1L, sentEpochMs = 2L)
        val withoutTiming = RadioFrame.Text("hi", languageCode = "en")
        assertEquals(7.toByte(), RadioFrameCodec.encode(withTiming)[0])
        assertEquals(8.toByte(), RadioFrameCodec.encode(withTiming.copy(isAlert = true))[0])
        assertEquals(3.toByte(), RadioFrameCodec.encode(withoutTiming)[0])
    }

    @Test
    fun textWithOnlyOneTimingFieldFallsBackToLanguageOnlyEncoding() {
        val frame = RadioFrame.Text("hi", languageCode = "en", speechEndEpochMs = 1L, sentEpochMs = null)
        val encoded = RadioFrameCodec.encode(frame)
        assertEquals(3.toByte(), encoded[0])
        assertEquals(RadioFrame.Text("hi", languageCode = "en"), RadioFrameCodec.decode(encoded))
    }

    @Test
    fun truncatedTimingFrameDecodesToNull() {
        val frame = RadioFrame.Text("hello", languageCode = "en", speechEndEpochMs = 1L, sentEpochMs = 2L)
        val encoded = RadioFrameCodec.encode(frame)
        val truncated = encoded.copyOfRange(0, encoded.size - 20)
        assertNull(RadioFrameCodec.decode(truncated))
    }

    @Test
    fun clockSyncPingPongRoundTrip() {
        val ping = RadioFrame.ClockSyncPing(originEpochMs = 1_726_000_000_000L)
        assertEquals(ping, RadioFrameCodec.decode(RadioFrameCodec.encode(ping)))
        assertEquals(9.toByte(), RadioFrameCodec.encode(ping)[0])

        val pong = RadioFrame.ClockSyncPong(originEpochMs = 1_726_000_000_000L, replyEpochMs = 1_726_000_000_555L)
        assertEquals(pong, RadioFrameCodec.decode(RadioFrameCodec.encode(pong)))
        assertEquals(10.toByte(), RadioFrameCodec.encode(pong)[0])
    }

    @Test
    fun clockSyncFramesWithWrongPayloadLengthDecodeToNull() {
        assertNull(RadioFrameCodec.decode(byteArrayOf(9, 1, 2, 3)))
        assertNull(RadioFrameCodec.decode(byteArrayOf(10, 1, 2, 3, 4, 5, 6, 7, 8)))
    }
}

