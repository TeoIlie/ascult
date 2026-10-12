package com.teoilie.ascult.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class AudioMathTest {
    @Test
    fun `silence reads as the meter floor`() {
        assertEquals(SILENCE_DBFS, ShortArray(1600).levelDbfs(), 0f)
        assertEquals(SILENCE_DBFS, ShortArray(0).levelDbfs(), 0f)
    }

    @Test
    fun `full scale reads as about 0 dBFS`() {
        assertEquals(0f, ShortArray(1600) { Short.MAX_VALUE }.levelDbfs(), 0.01f)
    }

    @Test
    fun `half scale reads as about -6 dBFS`() {
        assertEquals(-6.02f, ShortArray(1600) { (Short.MAX_VALUE / 2).toShort() }.levelDbfs(), 0.05f)
    }

    @Test
    fun `zero gain returns the same frame`() {
        val frame = shortArrayOf(1, -2, 3)
        assertSame(frame, frame.withGain(0))
    }

    @Test
    fun `6 dB roughly doubles and clips at the sample limits`() {
        val boosted = shortArrayOf(1000, -1000, 30_000, -30_000).withGain(6)
        assertArrayEquals(shortArrayOf(1995, -1995, Short.MAX_VALUE, Short.MIN_VALUE), boosted)
    }
}
