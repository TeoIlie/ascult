package com.teoilie.ascult.soniox

import org.junit.Assert.assertEquals
import org.junit.Test

class TranscriptTest {
    private fun ro(text: String, final: Boolean) = Token(text, isFinal = final, isTranslation = false)
    private fun en(text: String, final: Boolean) = Token(text, isFinal = final, isTranslation = true)

    @Test
    fun `final text appends and provisional text is replaced`() {
        val first = Transcript().update(listOf(ro("Cu", true), ro(" toții", false)))
        assertEquals(Transcript("Cu", " toții"), first)

        val second = first.update(listOf(ro(" toții", true), ro(" ne", false)))
        assertEquals(Transcript("Cu toții", " ne"), second)

        val third = second.update(emptyList())
        assertEquals(Transcript("Cu toții", ""), third)
    }

    @Test
    fun `withTokens routes original and translation into the last segment only`() {
        val earlier = Segment("a", Transcript("vechi"), Transcript("old"))
        val segments = listOf(earlier, Segment("b"))

        val updated = segments.withTokens(listOf(ro("Bună", true), en("Hello", true), en(" there", false)))

        assertEquals(earlier, updated[0])
        assertEquals(Transcript("Bună", ""), updated[1].romanian)
        assertEquals(Transcript("Hello", " there"), updated[1].english)
    }

    @Test
    fun `withoutProvisional keeps final text`() {
        val segments = listOf(Segment("a", Transcript("final", " prov"), Transcript("done", " maybe")))

        val cleared = segments.withoutProvisional().single()

        assertEquals(Transcript("final"), cleared.romanian)
        assertEquals(Transcript("done"), cleared.english)
    }

    @Test
    fun `plain text lists each segment with its label`() {
        val segments = listOf(
            Segment("Voice recognition · +0 dB · 19:42", Transcript(" Amin.", ""), Transcript(" Amen.", "")),
            Segment("Unprocessed · +6 dB · 19:50", Transcript("Doamne", " miluiește"), Transcript("Lord", "")),
        )

        assertEquals(
            "[Voice recognition · +0 dB · 19:42]\nRO: Amin.\nEN: Amen.\n\n" +
                "[Unprocessed · +6 dB · 19:50]\nRO: Doamne miluiește\nEN: Lord",
            segments.toPlainText(),
        )
    }
}
