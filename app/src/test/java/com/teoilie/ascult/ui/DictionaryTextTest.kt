package com.teoilie.ascult.ui

import com.teoilie.ascult.soniox.Dictionary
import org.junit.Assert.assertEquals
import org.junit.Test

class DictionaryTextTest {
    @Test
    fun `text form round trips a dictionary`() {
        val dictionary = Dictionary(
            general = listOf("domain" to "Orthodox sermon", "audio" to "reverberant church"),
            text = "A priest preaches.",
            terms = listOf("țarina", "Iisus Hristos"),
            translationTerms = listOf("Postul Mare" to "Great Lent", "țarina" to "field"),
        )

        assertEquals(dictionary, DictionaryText.from(dictionary).toDictionary())
    }

    @Test
    fun `blank lines and lines without both sides are skipped`() {
        val text = DictionaryText(
            terms = "  țarina \n\n roabă",
            translationTerms = "țarina = field\nno separator\n= missing source\nmissing target =",
            general = "domain: sermon\njust text",
        )

        val dictionary = text.toDictionary()

        assertEquals(listOf("țarina", "roabă"), dictionary.terms)
        assertEquals(listOf("țarina" to "field"), dictionary.translationTerms)
        assertEquals(listOf("domain" to "sermon"), dictionary.general)
    }

    @Test
    fun `only the first separator splits a line`() {
        val dictionary = DictionaryText(general = "note: time: 10:30", translationTerms = "a = b = c").toDictionary()

        assertEquals(listOf("note" to "time: 10:30"), dictionary.general)
        assertEquals(listOf("a" to "b = c"), dictionary.translationTerms)
    }
}
