package com.teoilie.ascult.history

import android.content.Context
import android.util.Log
import com.teoilie.ascult.audio.encodeM4a
import com.teoilie.ascult.settings.AudioSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TAG = "Ascult"
private const val ENTRY_FILE = "entry.json"
const val RECORDING_FILE = "audio.wav"

/** The recording once compressed after Save (see [encodeM4a]). */
const val COMPRESSED_FILE = "audio.m4a"

/**
 * History entries in the app's external files directory, one folder each
 * (/sdcard/Android/data/com.teoilie.ascult/files/history/20261018-101500/), where `adb pull` can reach them.
 * Deleted entries wait in `history/.trash` until the Undo is gone, and are removed at the next launch.
 */
class HistoryStore(context: Context, private val scope: CoroutineScope) {
    private val root = File(context.getExternalFilesDir(null), "history")
    private val trash = File(root, ".trash")

    /** Serialises all file changes, so an autosave, the final save and a delete never interleave. */
    private val writer = Dispatchers.IO.limitedParallelism(1)

    /** Compresses one recording at a time, beside the writer so saving never waits for it. */
    private val encoder = Dispatchers.Default.limitedParallelism(1)

    private val _entries = MutableStateFlow<List<HistoryEntry>>(emptyList())

    /** Saved entries, newest first. */
    val entries: StateFlow<List<HistoryEntry>> = _entries.asStateFlow()

    init {
        scope.launch(writer) { load() }
    }

    /** A new draft for a run starting now; its recording, if any, goes to [recordingFile]. */
    fun create(settings: AudioSettings, startedAt: Long): HistoryEntry {
        val base = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(startedAt))
        val id = generateSequence(1) { it + 1 }.map { if (it == 1) base else "$base-$it" }
            .first { !File(root, it).exists() && !File(trash, it).exists() }
        File(root, id).mkdirs()
        return HistoryEntry(
            id = id,
            title = defaultTitle(startedAt),
            startedAt = startedAt,
            source = settings.source,
            gainDb = settings.gainDb,
            translator = settings.translator,
            audio = if (settings.saveRecording) RECORDING_FILE else null,
        )
    }

    fun recordingFile(entry: HistoryEntry): File? = entry.audio?.let { File(File(root, entry.id), it) }

    /** Saves [entry] in the background; saved entries appear in [entries], and their recording is compressed. */
    fun write(entry: HistoryEntry) {
        scope.launch(writer) {
            val dir = File(root, entry.id)
            if (!dir.isDirectory) return@launch // deleted meanwhile
            // A copy from before compression finished (e.g. renamed meanwhile) keeps the compressed file.
            val current = if (entry.audio == RECORDING_FILE && !File(dir, RECORDING_FILE).exists()) entry.copy(audio = COMPRESSED_FILE) else entry
            writeJson(dir, current)
            if (current.saved) {
                publish(withSize(current))
                if (current.audio == RECORDING_FILE) compress(current.id)
            }
        }
    }

    /**
     * Replaces a saved entry's WAV with an .m4a about an eighth of its size. If the process dies
     * meanwhile, the WAV stays and is compressed at the next launch.
     */
    private fun compress(id: String) {
        scope.launch(encoder) {
            val dir = File(root, id)
            val wav = File(dir, RECORDING_FILE)
            val temp = File(dir, "$COMPRESSED_FILE.tmp")
            try {
                encodeM4a(wav, temp)
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't compress the recording of $id", e)
                temp.delete()
                return@launch
            }
            withContext(writer) {
                val entry = read(dir) // the latest title; null if deleted meanwhile
                if (entry == null || !temp.renameTo(File(dir, COMPRESSED_FILE))) {
                    temp.delete()
                    return@withContext
                }
                val compressed = entry.copy(audio = COMPRESSED_FILE)
                writeJson(dir, compressed)
                wav.delete()
                if (compressed.saved) publish(withSize(compressed))
                Log.i(TAG, "Compressed the recording of $id to ${File(dir, COMPRESSED_FILE).length() / 1000} kB")
            }
        }
    }

    /** Moves entries to the trash, from where [restore] can bring them back. */
    suspend fun delete(ids: Collection<String>) = withContext(writer) {
        trash.mkdirs()
        for (id in ids) File(root, id).renameTo(File(trash, id))
        _entries.update { list -> list.filterNot { it.id in ids } }
    }

    suspend fun restore(ids: Collection<String>) = withContext(writer) {
        for (id in ids) {
            val dir = File(root, id)
            if (!File(trash, id).renameTo(dir)) continue
            read(dir)?.takeIf { it.saved }?.let { publish(withSize(it)) }
        }
    }

    /** Removes deleted entries for good, once their Undo has gone. */
    fun emptyTrash() {
        scope.launch(writer) { trash.deleteRecursively() }
    }

    /** Removes a draft that has nothing worth keeping, without going through the trash. */
    fun discard(id: String) {
        scope.launch(writer) { File(root, id).deleteRecursively() }
    }

    /**
     * Reads every entry. Drafts left over from a process that died before Save or Delete are saved,
     * so a sermon is never lost without asking; they can still be deleted from History.
     */
    private fun load() {
        trash.deleteRecursively()
        val loaded = root.listFiles { file -> file.isDirectory }.orEmpty().mapNotNull { dir ->
            val entry = read(dir) ?: return@mapNotNull null
            if (entry.saved) entry else entry.copy(saved = true).also { writeJson(dir, it) }
        }
        _entries.value = loaded.map(::withSize).sortedByDescending { it.startedAt }
        for (entry in loaded) {
            File(root, entry.id).listFiles { file -> file.name.endsWith(".tmp") }?.forEach { it.delete() }
            if (entry.audio == RECORDING_FILE) compress(entry.id)
        }
    }

    private fun read(dir: File): HistoryEntry? {
        val file = File(dir, ENTRY_FILE)
        if (!file.isFile) return null
        return try {
            HistoryEntry.fromJson(dir.name, JSONObject(file.readText()))
        } catch (e: Exception) {
            Log.w(TAG, "Unreadable history entry ${dir.name}", e)
            null
        }
    }

    /** Writes via a temporary file, so a crash never leaves half a file. */
    private fun writeJson(dir: File, entry: HistoryEntry) {
        val temp = File(dir, "$ENTRY_FILE.tmp")
        temp.writeText(entry.toJson().toString(2) + "\n")
        temp.renameTo(File(dir, ENTRY_FILE))
    }

    private fun withSize(entry: HistoryEntry) = entry.copy(audioBytes = recordingFile(entry)?.length() ?: 0)

    private fun publish(entry: HistoryEntry) {
        _entries.update { list -> (list.filterNot { it.id == entry.id } + entry).sortedByDescending { it.startedAt } }
    }
}
