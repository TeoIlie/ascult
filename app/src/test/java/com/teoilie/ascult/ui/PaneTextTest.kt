package com.teoilie.ascult.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import com.teoilie.ascult.soniox.ClaudeText
import com.teoilie.ascult.soniox.Segment
import com.teoilie.ascult.soniox.Transcript
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PaneTextTest {
    private val provisional = SpanStyle(color = Color.Gray)
    private val segments = listOf(
        Segment(label = "Mic · +0 dB · 19:00", romanian = Transcript(" Bună ziua.", "")),
        Segment(label = "Mic · +6 dB · 19:30", romanian = Transcript("Iubiți credincioși,", " astăzi")),
    )

    @Test
    fun `segments are paragraphs of text only, without their labels`() {
        val pane = paneText(segments, Segment::romanian, provisional)
        assertEquals("Bună ziua.\n\nIubiți credincioși, astăzi", pane.text.text)
        assertEquals(listOf(0, "Bună ziua.\n\n".length), pane.segmentStarts)
    }

    @Test
    fun `only provisional text is styled`() {
        val pane = paneText(segments, Segment::romanian, provisional)
        val styled = pane.text.spanStyles.filter { it.start < it.end }.map { pane.text.text.substring(it.start, it.end) }
        assertEquals(listOf(" astăzi"), styled)
    }

    @Test
    fun `provisional text with no final text before it loses its leading space`() {
        val pane = paneText(listOf(Segment("x", english = Transcript("", " The problem"))), Segment::english, provisional)
        assertEquals("The problem", pane.text.text)
    }

    @Test
    fun `live caption shows Soniox English only while Claude translates the current segment`() {
        val live = Segment("x", english = Transcript(" The problem", " is"), claude = ClaudeText())
        assertEquals("The problem is", liveCaption(listOf(live), listening = true))
        assertNull(liveCaption(listOf(live), listening = false))
        assertNull(liveCaption(listOf(live.copy(claude = ClaudeText(failed = true))), listening = true))
        assertNull(liveCaption(listOf(live.copy(claude = null)), listening = true))
        assertNull(liveCaption(emptyList(), listening = true))
    }

    @Test
    fun `live caption keeps the end of long text, starting at a word`() {
        val words = List(100) { "word$it" }.joinToString(" ")
        val caption = liveCaption(listOf(Segment("x", english = Transcript(words), claude = ClaudeText())), listening = true)!!
        assertEquals(true, caption.startsWith("…word"))
        assertEquals(true, caption.endsWith("word99"))
        assertEquals(true, caption.length <= 301)
    }
}
