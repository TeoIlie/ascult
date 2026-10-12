package com.teoilie.ascult.session

import android.content.Context
import com.teoilie.ascult.settings.AudioSettings
import com.teoilie.ascult.soniox.Segment
import com.teoilie.ascult.soniox.toPlainText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Recordings and transcripts in the app's external files directory
 * (/sdcard/Android/data/com.teoilie.ascult/files/), where `adb pull` can reach them.
 */
class SessionFiles(private val context: Context, private val scope: CoroutineScope) {
    /** Serialises transcript writes so an autosave and the final save never interleave. */
    private val writer = Dispatchers.IO.limitedParallelism(1)

    /** Where the transcript since the last Clear is saved; named when its first session starts. */
    private var transcript: File? = null

    /** e.g. recordings/20261012-191500_UNPROCESSED_+12dB.wav */
    fun newRecording(settings: AudioSettings): File =
        newFile("recordings", "_${settings.source.name}_+${settings.gainDb}dB.wav")

    fun startTranscriptIfNeeded() {
        if (transcript == null) transcript = newFile("transcripts", ".txt")
    }

    /** The next session starts a new transcript file. */
    fun resetTranscript() {
        transcript = null
    }

    /** Overwrites the current transcript file in the background. */
    fun saveTranscript(segments: List<Segment>) {
        val file = transcript ?: return
        val text = segments.toPlainText()
        scope.launch(writer) { file.writeText(text + "\n") }
    }

    private fun newFile(dir: String, suffix: String): File {
        val parent = File(context.getExternalFilesDir(null), dir).apply { mkdirs() }
        return File(parent, timestamp("yyyyMMdd-HHmmss") + suffix)
    }
}

fun timestamp(pattern: String): String = SimpleDateFormat(pattern, Locale.US).format(Date())
