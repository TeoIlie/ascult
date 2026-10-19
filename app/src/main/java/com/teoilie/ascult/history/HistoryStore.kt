package com.teoilie.ascult.history

import android.content.Context
import android.util.Log
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

    /** Saves [entry] in the background; saved entries appear in [entries]. */
    fun write(entry: HistoryEntry) {
        scope.launch(writer) {
            val dir = File(root, entry.id)
            if (!dir.isDirectory) return@launch // deleted meanwhile
            writeJson(dir, entry)
            if (entry.saved) publish(withSize(entry))
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
