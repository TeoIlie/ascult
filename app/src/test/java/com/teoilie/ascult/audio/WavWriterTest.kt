package com.teoilie.ascult.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WavWriterTest {
    @Test
    fun `header describes 16 kHz mono PCM16 and the data that follows`() {
        val file = File.createTempFile("wav-writer-test", ".wav").apply { deleteOnExit() }
        WavWriter(file).use { wav ->
            wav.write(shortArrayOf(1, 2, 3))
            wav.write(shortArrayOf(-1))
        }

        val bytes = file.readBytes()
        val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        fun tag(offset: Int) = String(bytes, offset, 4, Charsets.US_ASCII)

        assertEquals(44 + 8, bytes.size)
        assertEquals("RIFF", tag(0))
        assertEquals(36 + 8, header.getInt(4))
        assertEquals("WAVE", tag(8))
        assertEquals("fmt ", tag(12))
        assertEquals(1, header.getShort(20).toInt()) // PCM
        assertEquals(1, header.getShort(22).toInt()) // mono
        assertEquals(SAMPLE_RATE, header.getInt(24))
        assertEquals(SAMPLE_RATE * 2, header.getInt(28))
        assertEquals(16, header.getShort(34).toInt())
        assertEquals("data", tag(36))
        assertEquals(8, header.getInt(40))

        val samples = ShortArray(4).also { header.position(44); header.asShortBuffer().get(it) }
        assertArrayEquals(shortArrayOf(1, 2, 3, -1), samples)
    }
}
