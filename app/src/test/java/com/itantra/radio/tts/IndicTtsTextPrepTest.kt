package com.itantra.radio.tts

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class IndicTtsTextPrepTest {

    private val charToId: Map<Char, Int> = run {
        val json = JSONObject(File("../ml/tts/onnx_export/char_to_id.json").readText(Charsets.UTF_8))
        val map = HashMap<Char, Int>()
        for (key in json.keys()) {
            if (key.length == 1) map[key[0]] = json.getInt(key)
        }
        map
    }

    private fun ids(text: String): List<Long> = IndicTtsTextPrep.toTokenIds(text, charToId).toList()

    @Test
    fun tokenIdsMatchReferenceTokenizer_simpleSentence() {
        assertEquals(
            listOf(64L, 57, 57, 14, 63, 83, 47, 86, 14, 28, 63, 78, 15),
            ids("मदद भेजो अभी!"),
        )
    }

    @Test
    fun tokenIdsMatchReferenceTokenizer_newlineBecomesSingleSpace() {
        assertEquals(
            listOf(60L, 76, 59, 78, 14, 41, 55, 88, 64, 14, 74, 86, 14, 42, 65, 76, 14, 74, 84, 14, 47, 68, 88, 57, 78, 14, 29, 38),
            ids("पानी खत्म हो गया है\nजल्दी आओ"),
        )
    }

    @Test
    fun tokenIdsMatchReferenceTokenizer_surroundingWhitespaceStripped() {
        assertEquals(listOf(59L, 64, 73, 88, 55, 83), ids("  नमस्ते  "))
    }

    @Test
    fun tokenIdsMatchReferenceTokenizer_dandaDroppedCommaKept() {
        assertEquals(
            listOf(37L, 40, 88, 73, 78, 47, 59, 14, 40, 64, 14, 74, 84, 16, 14, 52, 85, 40, 88, 50, 66, 14, 45, 76, 74, 77, 35),
            ids("ऑक्सीजन कम है, डॉक्टर चाहिए।"),
        )
    }

    @Test
    fun tokenIdsMatchReferenceTokenizer_symbolReplacementAndAuxRemoval() {
        assertEquals(
            listOf(
                74L, 64, 14, 19, 20, 14, 68, 86, 42, 14, 61, 26, 73, 83, 14, 74, 84, 26, 16, 14,
                47, 68, 88, 57, 78, 14, 47, 68, 88, 57, 78, 14, 29, 38, 16, 14, 64, 57, 57,
            ),
            ids("हम 28 लोग (फंसे) हैं; जल्दी-जल्दी आओ: मदद"),
        )
    }

    @Test
    fun latinLettersAreDroppedLikeReferenceTokenizer() {
        assertEquals(ids("भेजो"), ids("help भेजो").drop(1))
    }

    @Test
    fun blankOrUnknownOnlyTextYieldsNoTokens() {
        assertTrue(ids("   ").isEmpty())
        assertTrue(ids("xyz").isEmpty())
    }

    @Test
    fun splitsOnDandaAndQuestionMarks() {
        val chunks = IndicTtsTextPrep.splitIntoSpeakableChunks("मदद भेजो। जल्दी आओ? ठीक है!")
        assertEquals(listOf("मदद भेजो।", "जल्दी आओ?", "ठीक है!"), chunks.map { it.trim() })
    }

    @Test
    fun shortTextStaysOneChunk() {
        assertEquals(1, IndicTtsTextPrep.splitIntoSpeakableChunks("पानी खत्म हो गया है").size)
    }

    @Test
    fun emptyAndPunctuationOnlyTextYieldsNoChunks() {
        assertTrue(IndicTtsTextPrep.splitIntoSpeakableChunks("").isEmpty())
        assertTrue(IndicTtsTextPrep.splitIntoSpeakableChunks("।।।  \n").isEmpty())
    }

    @Test
    fun longSentenceIsBrokenIntoBoundedChunksWithoutLosingWords() {
        val words = List(60) { "मदद$it" }
        val sentence = words.joinToString(" ")
        val chunks = IndicTtsTextPrep.splitIntoSpeakableChunks(sentence)
        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.length <= IndicTtsTextPrep.MAX_CHUNK_CHARS })
        assertEquals(words, chunks.flatMap { it.split(" ") })
    }

    @Test
    fun singleHugeWordlessRunStillTerminates() {
        val chunks = IndicTtsTextPrep.splitIntoSpeakableChunks("क".repeat(400))
        assertEquals(1, chunks.size)
    }
}
