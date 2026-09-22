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

    @Test
    fun collapsesAPhraseRepeatedBackToBack() {
        assertEquals("what is your name", SttTextFilter.clean("what is your name what is your name"))
        assertEquals("What is your name?", SttTextFilter.clean("What is your name? What is your name?"))
        assertEquals("we need water", SttTextFilter.clean("we need water we need water we need water"))
    }

    @Test
    fun collapsesAWordRepeatedThreeOrMoreTimesButNotTwice() {
        assertEquals("help", SttTextFilter.clean("help help help"))
        assertEquals("very very good", SttTextFilter.clean("very very good"))
        assertEquals("no no", SttTextFilter.clean("no no"))
    }

    @Test
    fun leavesNormalSentencesWithRepeatedWordsAlone() {
        assertEquals("the road is blocked by the road", SttTextFilter.clean("the road is blocked by the road"))
        assertEquals("go go now go go now please", SttTextFilter.clean("go go now go go now please"))
    }

    @Test
    fun collapsesRepeatedHindiPhrases() {
        assertEquals("मदद भेजो अभी", SttTextFilter.clean("मदद भेजो अभी मदद भेजो अभी"))
    }

    @Test
    fun doesNotMergeHindiWordsThatDifferOnlyInVowelSigns() {
        assertEquals("मदद भेजो भजा भेजो", SttTextFilter.clean("मदद भेजो भजा भेजो"))
        assertEquals("पानी पानी", SttTextFilter.clean("पानी पानी"))
        assertEquals("कि की", SttTextFilter.clean("कि की कि की कि की"))
    }
}

