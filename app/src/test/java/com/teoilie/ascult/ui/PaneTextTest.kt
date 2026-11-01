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
    private val newest = SpanStyle(color = Color.Blue)
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
    fun `live caption shows Soniox English only when shown and Claude translates the current segment`() {
        val live = Segment("x", english = Transcript(" The problem", " is"), claude = ClaudeText())
        assertEquals("The problem is", liveCaption(listOf(live), show = true))
        assertNull(liveCaption(listOf(live), show = false))
        assertNull(liveCaption(listOf(live.copy(claude = ClaudeText(failed = true))), show = true))
        assertNull(liveCaption(listOf(live.copy(claude = null)), show = true))
        assertNull(liveCaption(emptyList(), show = true))
    }

    @Test
    fun `live caption keeps the end of long text, starting at a word`() {
        val words = List(100) { "word$it" }.joinToString(" ")
        val caption = liveCaption(listOf(Segment("x", english = Transcript(words), claude = ClaudeText())), show = true)!!
        assertEquals(true, caption.startsWith("…word"))
        assertEquals(true, caption.endsWith("word99"))
        assertEquals(true, caption.length <= 301)
    }

    @Test
    fun `Claude's newest English reply is highlighted`() {
        val segment = Segment("x", claude = ClaudeText("Amen. Lord, have mercy.", newestFrom = "Amen. ".length))
        assertEquals(listOf("Lord, have mercy."), highlighted(listOf(segment), PaneSide.ENGLISH))
    }

    @Test
    fun `only the Romanian of the newest reply is highlighted, without the space before it`() {
        val segment = Segment(
            "x",
            romanian = Transcript(" Amin. Doamne miluiește. Bine", " ați venit"),
            claude = ClaudeText("Amen. Lord, have mercy.", translatedUpTo = 24, newestRomanianFrom = 6),
        )
        val pane = paneText(listOf(segment), Segment::romanian, provisional, PaneSide.ROMANIAN::newest, newest)
        assertEquals("Amin. Doamne miluiește. Bine ați venit", pane.text.text)
        assertEquals(listOf("Doamne miluiește."), pane.styled(newest))
        assertEquals(listOf(" ați venit"), pane.styled(provisional))
    }

    @Test
    fun `Claude's first reply is all highlighted`() {
        assertEquals(listOf("Amen."), highlighted(listOf(Segment("x", claude = ClaudeText("Amen."))), PaneSide.ENGLISH))
    }

    @Test
    fun `only the last segment's newest reply counts`() {
        val earlier = Segment("a", claude = ClaudeText("Old one. Old two.", newestFrom = 9))
        val current = Segment("b", claude = ClaudeText("New one. New two.", newestFrom = 9))
        assertEquals(listOf("New two."), highlighted(listOf(earlier, current), PaneSide.ENGLISH))
    }

    @Test
    fun `nothing is highlighted without Claude or after it fails`() {
        val romanian = Transcript("Amin. Doamne miluiește.")
        val claude = ClaudeText("Amen. Lord, have mercy.", translatedUpTo = 23, newestFrom = 6, newestRomanianFrom = 5)
        for (segment in listOf(Segment("x", romanian), Segment("x", romanian, claude = claude.copy(failed = true)))) {
            for (side in PaneSide.entries) assertEquals(emptyList<String>(), highlighted(listOf(segment), side))
        }
    }

    @Test
    fun `an empty reply highlights nothing`() {
        assertEquals(emptyList<String>(), highlighted(listOf(Segment("x", claude = ClaudeText("Amen. ", newestFrom = 6))), PaneSide.ENGLISH))
    }

    @Test
    fun `a range past the text is clamped`() {
        val segment = Segment("x", romanian = Transcript("Amin."), claude = ClaudeText(translatedUpTo = 99, newestRomanianFrom = 3))
        assertEquals(listOf("n."), highlighted(listOf(segment), PaneSide.ROMANIAN))
    }

    private fun highlighted(segments: List<Segment>, side: PaneSide) =
        paneText(segments, side.transcriptOf, provisional, side::newest, newest).styled(newest)

    private fun PaneText.styled(style: SpanStyle) =
        text.spanStyles.filter { it.item == style && it.start < it.end }.map { text.text.substring(it.start, it.end) }
}
