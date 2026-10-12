package com.teoilie.ascult.audio

import android.Manifest
import android.media.AudioFormat
import android.media.AudioRecord
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

const val SAMPLE_RATE = 16_000
const val FRAME_MS = 100
const val FRAME_SAMPLES = SAMPLE_RATE * FRAME_MS / 1000

/** Lowest level shown by the meter; anything quieter reads as silence. */
const val SILENCE_DBFS = -80f

/** Microphone capture as a flow of 100 ms frames of 16 kHz mono PCM16, the format sent to Soniox. */
class AudioCapture(private val source: Int) {

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun frames(): Flow<ShortArray> = flow {
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        val record = AudioRecord(
            source,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            max(minBuffer, FRAME_SAMPLES * 2 * 4),
        )
        check(record.state == AudioRecord.STATE_INITIALIZED) { "Microphone could not be opened" }

        try {
            record.startRecording()
            while (currentCoroutineContext().isActive) {
                val frame = ShortArray(FRAME_SAMPLES)
                var read = 0
                while (read < frame.size) {
                    val n = record.read(frame, read, frame.size - read)
                    check(n >= 0) { "Microphone read failed ($n)" }
                    read += n
                }
                emit(frame)
            }
        } finally {
            record.stop()
            record.release()
        }
    }.flowOn(Dispatchers.IO)
}

/** RMS level of the frame in dBFS, floored at [SILENCE_DBFS]. */
fun ShortArray.levelDbfs(): Float {
    if (isEmpty()) return SILENCE_DBFS
    var sum = 0.0
    for (sample in this) sum += sample.toDouble() * sample
    val rms = sqrt(sum / size) / Short.MAX_VALUE
    return if (rms <= 0.0) SILENCE_DBFS else max(SILENCE_DBFS, (20 * log10(rms)).toFloat())
}

/** Software gain with clipping. Raises quiet input to a usable level; it does not improve signal-to-noise. */
fun ShortArray.withGain(gainDb: Int): ShortArray {
    if (gainDb == 0) return this
    val factor = 10.0.pow(gainDb / 20.0)
    return ShortArray(size) { i ->
        (this[i] * factor).roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
    }
}
