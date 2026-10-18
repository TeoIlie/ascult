package com.teoilie.ascult.ui

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.lerp
import org.junit.Assert.assertEquals
import org.junit.Test

class ColorSchemeLerpTest {
    private val light = lightColorScheme()
    private val dark = darkColorScheme()

    // ColorScheme.toString lists every colour, so these catch a colour left out of lerp().
    @Test
    fun `the ends of the fade are the two schemes exactly`() {
        assertEquals(light.toString(), light.lerp(dark, 0f).toString())
        assertEquals(dark.toString(), light.lerp(dark, 1f).toString())
    }

    @Test
    fun `halfway mixes every colour`() {
        val half = light.lerp(dark, 0.5f)
        assertEquals(lerp(light.background, dark.background, 0.5f), half.background)
        assertEquals(lerp(light.onTertiaryFixed, dark.onTertiaryFixed, 0.5f), half.onTertiaryFixed)
    }
}
