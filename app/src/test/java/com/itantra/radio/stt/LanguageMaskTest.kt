package com.itantra.radio.stt

import org.json.JSONArray
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class LanguageMaskTest {

    @Test
    fun selectsPositionsWhereMaskIsTrue() {
        val mask = JSONArray("[true, false, true, true, false]")
        assertArrayEquals(intArrayOf(0, 2, 3), LanguageMask.selectedIndices(mask))
    }

    @Test
    fun allFalseSelectsNothing() {
        assertEquals(0, LanguageMask.selectedIndices(JSONArray("[false, false]")).size)
    }

    @Test
    fun allTrueSelectsEverythingInOrder() {
        assertArrayEquals(intArrayOf(0, 1, 2), LanguageMask.selectedIndices(JSONArray("[true, true, true]")))
    }
}
