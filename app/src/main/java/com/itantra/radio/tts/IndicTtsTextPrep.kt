package com.itantra.radio.tts

import java.text.Normalizer
import java.util.Locale

object IndicTtsTextPrep {
    const val MAX_CHUNK_CHARS = 60

    private val whitespace = Regex("[\\s\\u00a0\\u0085\\u1680\\u2000-\\u200a\\u2028\\u2029\\u202f\\u205f\\u3000\\u001c-\\u001f]+")
    private val auxSymbols = Regex("[<>()\\[\\]\"]+")
    private val sentenceEnd = Regex("(?<=[।॥.?!\\n])")
    private val clauseBreak = Regex("(?<=[,;:])\\s+")

    fun normalize(text: String): String {
        var cleaned = Normalizer.normalize(text, Normalizer.Form.NFC)
        cleaned = cleaned.lowercase(Locale.ROOT)
        cleaned = cleaned.replace(";", ",").replace("-", " ").replace(":", ",")
        cleaned = auxSymbols.replace(cleaned, "")
        return whitespace.replace(cleaned, " ").trim()
    }

    fun toTokenIds(text: String, charToId: Map<Char, Int>): LongArray {
        val ids = ArrayList<Long>(text.length)
        for (ch in normalize(text)) {
            val id = charToId[ch] ?: continue
            ids.add(id.toLong())
        }
        return ids.toLongArray()
    }

    fun splitIntoSpeakableChunks(text: String, maxChars: Int = MAX_CHUNK_CHARS): List<String> {
        val chunks = ArrayList<String>()
        for (sentence in sentenceEnd.split(text)) {
            if (!isSpeakable(sentence)) continue
            chunks.addAll(breakLongSentence(sentence.trim(), maxChars))
        }
        return chunks.filter { isSpeakable(it) }
    }

    private fun isSpeakable(text: String): Boolean = text.any { it.isLetterOrDigit() }

    private fun breakLongSentence(sentence: String, maxChars: Int): List<String> {
        if (sentence.length <= maxChars) return listOf(sentence)
        val pieces = ArrayList<String>()
        var current = StringBuilder()
        for (clause in clauseBreak.split(sentence)) {
            for (part in breakOnWords(clause, maxChars)) {
                if (current.isNotEmpty() && current.length + part.length + 1 > maxChars) {
                    pieces.add(current.toString())
                    current = StringBuilder()
                }
                if (current.isNotEmpty()) current.append(' ')
                current.append(part)
            }
        }
        if (current.isNotEmpty()) pieces.add(current.toString())
        return pieces
    }

    private fun breakOnWords(clause: String, maxChars: Int): List<String> {
        if (clause.length <= maxChars) return listOf(clause)
        val parts = ArrayList<String>()
        var current = StringBuilder()
        for (word in clause.split(' ')) {
            if (current.isNotEmpty() && current.length + word.length + 1 > maxChars) {
                parts.add(current.toString())
                current = StringBuilder()
            }
            if (current.isNotEmpty()) current.append(' ')
            current.append(word)
        }
        if (current.isNotEmpty()) parts.add(current.toString())
        return parts
    }
}
