package com.teoilie.ascult.translation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SentenceChunkerTest {
    private val first = "Dacă nu ne îngrijim ogorul, nu mâncăm toamna."
    private val second = " Așa este și în viața duhovnicească."

    @Test
    fun `waits until a sentence ends`() {
        assertNull(nextChunk("Dacă nu ne îngrijim ogorul,", 0))
        assertNull(nextChunk("", 0))
    }

    @Test
    fun `sends up to the last sentence end, trimmed, and continues from there`() {
        val final = "$first$second Iar așteptând"
        val chunk = nextChunk(final, 0)!!
        assertEquals(Chunk("$first$second".trim(), "$first$second".length), chunk)

        assertNull(nextChunk(final, chunk.end))
        val longer = "$final și așteptând, pământul se otrăvește."
        assertEquals(Chunk("Iar așteptând și așteptând, pământul se otrăvește.", longer.length), nextChunk(longer, chunk.end))
    }

    @Test
    fun `a short trailing sentence waits for the next one`() {
        val final = "$first De aceea."
        assertEquals(Chunk(first, first.length), nextChunk(final, 0))
        assertNull(nextChunk(final, first.length))

        val more = "$final După cum știm fiecare, trebuie să muncim."
        assertEquals("De aceea. După cum știm fiecare, trebuie să muncim.", nextChunk(more, first.length)!!.text)
    }

    @Test
    fun `closing quotes stay with their sentence`() {
        val final = "El a spus: „Pace vouă tuturor.” Apoi"
        assertEquals(Chunk("El a spus: „Pace vouă tuturor.”", final.indexOf(" Apoi")), nextChunk(final, 0))
    }

    @Test
    fun `a long run-on is cut after its last comma`() {
        val clause = List(38) { "cuvânt" }.joinToString(" ")
        val final = "$clause, și încă unul și altul"
        assertEquals(Chunk("$clause,", clause.length + 1), nextChunk(final, 0))
    }

    @Test
    fun `a long run-on without a comma leaves out its last, possibly incomplete, word`() {
        val words = List(45) { "cuvânt" }.joinToString(" ")
        val final = "$words mân"
        assertEquals(Chunk(words, words.length), nextChunk(final, 0))
    }

    @Test
    fun `flush sends everything left`() {
        val final = "$first De aceea"
        assertEquals(Chunk("De aceea", final.length), nextChunk(final, first.length, flush = true))
        assertNull(nextChunk(final, final.length, flush = true))
    }
}
