package com.teoilie.ascult.audio

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Our WAV files have the plain 44-byte header, then the samples. */
internal const val WAV_HEADER_BYTES = 44

/** Writes 16 kHz mono PCM16 to a WAV file; the header sizes are filled in on [close]. */
class WavWriter(file: File) : Closeable {
    private val out = RandomAccessFile(file, "rw").apply {
        setLength(0)
        write(ByteArray(WAV_HEADER_BYTES))
    }
    private var dataBytes = 0L

    fun write(frame: ShortArray) {
        val buffer = ByteBuffer.allocate(frame.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        buffer.asShortBuffer().put(frame)
        out.write(buffer.array())
        dataBytes += buffer.capacity()
    }

    override fun close() {
        out.seek(0)
        out.write(header(dataBytes))
        out.close()
    }

    private fun header(dataBytes: Long): ByteArray = ByteBuffer.allocate(WAV_HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray()); putInt((36 + dataBytes).toInt())
        put("WAVE".toByteArray())
        put("fmt ".toByteArray()); putInt(16)
        putShort(1) // PCM
        putShort(1) // mono
        putInt(SAMPLE_RATE)
        putInt(SAMPLE_RATE * 2) // byte rate
        putShort(2) // block align
        putShort(16) // bits per sample
        put("data".toByteArray()); putInt(dataBytes.toInt())
    }.array()
}

/** Passes frames through unchanged while saving them to [file] as WAV. */
fun Flow<ShortArray>.recordTo(file: File): Flow<ShortArray> = flow {
    WavWriter(file).use { wav ->
        collect { frame ->
            wav.write(frame)
            emit(frame)
        }
    }
}
