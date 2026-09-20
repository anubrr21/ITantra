package com.itantra.radio.stt

object SttTextFilter {
    private val bracketedNoise = Regex("^[\\[(].*[\\])]$")

    fun clean(raw: String): String {
        val text = raw.trim()
        if (text.isEmpty()) return ""
        if (bracketedNoise.matches(text)) return ""
        if (text.none { it.isLetterOrDigit() }) return ""
        return text
    }
}
