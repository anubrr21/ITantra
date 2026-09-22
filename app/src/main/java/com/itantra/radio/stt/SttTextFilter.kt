package com.itantra.radio.stt

object SttTextFilter {
    private const val MAX_PHRASE_WORDS = 8
    private const val MIN_SINGLE_WORD_REPEATS = 3

    private val bracketedNoise = Regex("^[\\[(].*[\\])]$")
    private val whitespace = Regex("\\s+")

    fun clean(raw: String): String {
        val text = raw.trim()
        if (text.isEmpty()) return ""
        if (bracketedNoise.matches(text)) return ""
        if (text.none { it.isLetterOrDigit() }) return ""
        return collapseRepeatedPhrase(text)
    }

    fun collapseRepeatedPhrase(text: String): String {
        val words = text.split(whitespace).filter { it.isNotEmpty() }
        if (words.size < 2) return text
        val keys = words.map { key(it) }
        for (phraseLength in 1..minOf(MAX_PHRASE_WORDS, words.size / 2)) {
            if (words.size % phraseLength != 0) continue
            val repeats = words.size / phraseLength
            val minimumRepeats = if (phraseLength == 1) MIN_SINGLE_WORD_REPEATS else 2
            if (repeats < minimumRepeats) continue
            val phrase = keys.subList(0, phraseLength)
            val allEqual = (1 until repeats).all { block ->
                keys.subList(block * phraseLength, (block + 1) * phraseLength) == phrase
            }
            if (allEqual) return words.subList(0, phraseLength).joinToString(" ")
        }
        return text
    }

    private fun key(word: String): String = word.lowercase().filter { it.isLetterOrDigit() || isCombiningMark(it) }

    private fun isCombiningMark(ch: Char): Boolean {
        val type = Character.getType(ch)
        return type == Character.NON_SPACING_MARK.toInt() || type == Character.COMBINING_SPACING_MARK.toInt()
    }
}
