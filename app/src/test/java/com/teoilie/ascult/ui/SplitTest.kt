package com.teoilie.ascult.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class SplitTest {
    @Test
    fun `near either end snaps that pane shut`() {
        assertEquals(0f, snapSplit(0.1f), 0f)
        assertEquals(0f, snapSplit(-0.2f), 0f)
        assertEquals(1f, snapSplit(0.9f), 0f)
        assertEquals(1f, snapSplit(1.3f), 0f)
    }

    @Test
    fun `in between stays where it was dropped`() {
        assertEquals(0.15f, snapSplit(0.15f), 0f)
        assertEquals(0.5f, snapSplit(0.5f), 0f)
        assertEquals(0.85f, snapSplit(0.85f), 0f)
    }
}
