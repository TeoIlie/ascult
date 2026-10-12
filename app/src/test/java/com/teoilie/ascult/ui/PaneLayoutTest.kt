package com.teoilie.ascult.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class PaneLayoutTest {
    // Segments start at 0, 100 and 300 px; the text is 400 px tall.
    private val layout = PaneLayout(segmentTops = listOf(0f, 100f, 300f), height = 400f)

    @Test
    fun `locate finds the segment and the fraction through it`() {
        assertEquals(0 to 0.5f, layout.locate(50f))
        assertEquals(1 to 0f, layout.locate(100f))
        assertEquals(1 to 0.5f, layout.locate(200f))
        assertEquals(2 to 0.5f, layout.locate(350f))
    }

    @Test
    fun `position is the inverse of locate`() {
        for (y in listOf(0f, 50f, 100f, 250f, 399f)) {
            val (segment, fraction) = layout.locate(y)
            assertEquals(y, layout.position(segment, fraction), 0.001f)
        }
    }

    @Test
    fun `out of range positions are clamped`() {
        assertEquals(0 to 0f, layout.locate(-10f))
        assertEquals(2 to 1f, layout.locate(500f))
        assertEquals(400f, layout.position(7, 0.5f), 0f) // a segment the other pane lacks: its end
    }

    @Test
    fun `empty layout maps everything to the top`() {
        val empty = PaneLayout()
        assertEquals(0 to 0f, empty.locate(10f))
        assertEquals(0f, empty.position(0, 0.5f), 0f)
    }
}
