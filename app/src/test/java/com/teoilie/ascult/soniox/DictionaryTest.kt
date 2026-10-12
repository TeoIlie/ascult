package com.teoilie.ascult.soniox

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Soniox rejects a `context` larger than about 10,000 characters (8,000 tokens). */
private const val SONIOX_CONTEXT_LIMIT_CHARS = 10_000

class DictionaryTest {
    @Test
    fun `json round trip keeps every field`() {
        val dictionary = Dictionary(
            general = listOf("domain" to "Orthodox sermon"),
            text = "Background",
            terms = listOf("țarina", "Iisus Hristos"),
            translationTerms = listOf("Postul Mare" to "Great Lent"),
        )

        assertEquals(dictionary, Dictionary.fromJson(JSONObject(dictionary.toJson().toString())))
    }

    @Test
    fun `missing fields read as empty`() {
        assertEquals(Dictionary(), Dictionary.fromJson(JSONObject("{}")))
    }

    @Test
    fun `bundled dictionary parses and fits the Soniox context limit`() {
        // Unit tests run with the module directory (app/) as the working directory.
        val json = JSONObject(File("src/main/assets/dictionary.json").readText())
        val dictionary = Dictionary.fromJson(json)

        assertTrue(dictionary.terms.isNotEmpty())
        assertTrue(dictionary.translationTerms.isNotEmpty())
        val sent = dictionary.toJson().toString()
        assertTrue("context is ${sent.length} chars", sent.length < SONIOX_CONTEXT_LIMIT_CHARS)
    }
}
