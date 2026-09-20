package com.itantra.radio.stt

import org.junit.Assert.assertEquals
import org.junit.Test

class SttTextFilterTest {

    @Test
    fun keepsRealSentencesUntouchedApartFromTrimming() {
        assertEquals("Send help immediately.", SttTextFilter.clean("  Send help immediately.  "))
    }

    @Test
    fun dropsWhisperNonSpeechMarkers() {
        assertEquals("", SttTextFilter.clean("[ Silence ]"))
        assertEquals("", SttTextFilter.clean("(buzzer)"))
        assertEquals("", SttTextFilter.clean("[BLANK_AUDIO]"))
    }

    @Test
    fun dropsEmptyAndSymbolOnlyText() {
        assertEquals("", SttTextFilter.clean(""))
        assertEquals("", SttTextFilter.clean("   "))
        assertEquals("", SttTextFilter.clean("..."))
        assertEquals("", SttTextFilter.clean("♪"))
    }

    @Test
    fun keepsSentencesThatMerelyContainBrackets() {
        assertEquals("Call (the) team", SttTextFilter.clean("Call (the) team"))
    }
}
