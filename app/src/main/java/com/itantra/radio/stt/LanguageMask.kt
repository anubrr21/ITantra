package com.itantra.radio.stt

import org.json.JSONArray

object LanguageMask {
    fun selectedIndices(mask: JSONArray): IntArray {
        val selected = ArrayList<Int>(mask.length())
        for (i in 0 until mask.length()) {
            if (mask.getBoolean(i)) selected.add(i)
        }
        return selected.toIntArray()
    }
}
