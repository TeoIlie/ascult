package com.teoilie.ascult.audio

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.io.IOException

/** Speech stays clear at this rate: about 14 MB an hour, against 115 MB for the WAV. */
private const val AAC_BIT_RATE = 32_000

private const val TIMEOUT_US = 10_000L

/**
 * Encodes one of our 16 kHz mono PCM16 WAV files ([WavWriter]) to AAC-LC in an MPEG-4 file (.m4a).
 * Blocking and CPU-heavy (seconds per hour of audio): call it off the main thread.
 */
fun encodeM4a(wav: File, out: File) {
    val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, 1).apply {
        setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        setInteger(MediaFormat.KEY_BIT_RATE, AAC_BIT_RATE)
    }
    val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
    val muxer = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    var muxing = false
    try {
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        wav.inputStream().buffered().use { input ->
            if (input.skip(WAV_HEADER_BYTES.toLong()) != WAV_HEADER_BYTES.toLong()) throw IOException("Not a WAV file: $wav")
            val info = MediaCodec.BufferInfo()
            var bytesRead = 0L
            var inputDone = false
            var track = -1
            while (true) {
                if (!inputDone) {
                    val index = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (index >= 0) {
                        val buffer = codec.getInputBuffer(index)!!
                        val chunk = ByteArray(buffer.remaining() and 1.inv()) // whole samples only
                        val count = input.read(chunk).coerceAtLeast(0)
                        buffer.put(chunk, 0, count)
                        val timeUs = bytesRead / 2 * 1_000_000 / SAMPLE_RATE
                        bytesRead += count
                        inputDone = count == 0
                        val flags = if (inputDone) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0
                        codec.queueInputBuffer(index, 0, count, timeUs, flags)
                    }
                }
                val index = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                when {
                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        track = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                        muxing = true
                    }
                    index >= 0 -> {
                        val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        if (!isConfig && info.size > 0 && muxing) {
                            muxer.writeSampleData(track, codec.getOutputBuffer(index)!!, info)
                        }
                        codec.releaseOutputBuffer(index, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
            }
        }
    } finally {
        codec.release()
        try {
            if (muxing) muxer.stop()
        } finally {
            muxer.release()
        }
    }
}
