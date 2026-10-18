package com.teoilie.ascult.ui

import com.teoilie.ascult.audio.SILENCE_DBFS
import org.junit.Assert.assertEquals
import org.junit.Test

class LevelMeterTest {
    @Test
    fun `silence is empty and full scale is full`() {
        assertEquals(0f, levelFraction(SILENCE_DBFS), 0f)
        assertEquals(1f, levelFraction(0f), 0f)
        assertEquals(0.5f, levelFraction(SILENCE_DBFS / 2), 0.0001f)
    }

    @Test
    fun `out of range levels are clamped`() {
        assertEquals(0f, levelFraction(SILENCE_DBFS - 20f), 0f)
        assertEquals(0f, levelFraction(Float.NEGATIVE_INFINITY), 0f)
        assertEquals(1f, levelFraction(6f), 0f)
    }
}
