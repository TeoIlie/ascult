package com.teoilie.ascult.history

import android.util.Log
import com.teoilie.ascult.LOG_TAG
import com.teoilie.ascult.audio.encodeM4a
import com.teoilie.ascult.settings.AudioSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private const val ENTRY_FILE = "entry.json"

/** The recording while listening, and until it is compressed after Save. */
const val RECORDING_FILE = "audio.wav"

/** The recording once compressed (see [encodeM4a]). */
const val COMPRESSED_FILE = "audio.m4a"

private val ID_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

/**
 * History entries under [root], one folder each (`history/20261018-101500/` in the app's external
 * files directory, where `adb pull` can reach them). Deleted entries wait in `history/.trash` until
 * their Undo has gone ([forget]); whatever is left there goes at the next launch.
 */
class HistoryStore(private val root: File, private val scope: CoroutineScope) {
    private val trash = File(root, ".trash")

    /** Serialises all file changes, so an autosave, the final save and a delete never interleave. */
    private val writer = Dispatchers.IO.limitedParallelism(1)

    /** Compresses one recording at a time, beside the writer so saving never waits for it. */
    private val encoder = Dispatchers.Default.limitedParallelism(1)

    /** Entries whose recording is being compressed; only touched on [writer]. */
    private val compressing = mutableSetOf<String>()

    private val _entries = MutableStateFlow<List<HistoryEntry>>(emptyList())

    /** Saved entries, newest first. */
    val entries: StateFlow<List<HistoryEntry>> = _entries.asStateFlow()

    /** Reading the entries; queued first on the writer, so every later change comes after it. */
    val loading: Job = scope.launch(writer) { load() }

    /** A new draft for a run starting at [startedAt]; its recording, if any, goes to [recordingFile]. Blocking. */
    fun create(settings: AudioSettings, startedAt: Long, zone: ZoneId = ZoneId.systemDefault()): HistoryEntry {
        val base = ID_FORMAT.format(Instant.ofEpochMilli(startedAt).atZone(zone))
        val id = generateSequence(1) { it + 1 }.map { if (it == 1) base else "$base-$it" }
            .first { !File(root, it).exists() && !File(trash, it).exists() }
        File(root, id).mkdirs()
        return HistoryEntry(
            id = id,
            title = defaultTitle(startedAt, zone),
            startedAt = startedAt,
            source = settings.source,
            gainDb = settings.gainDb,
            translator = settings.translator,
            audio = if (settings.saveRecording) RECORDING_FILE else null,
        )
    }

    fun recordingFile(entry: HistoryEntry): File? = entry.audio?.let { File(File(root, entry.id), it) }

    /** Saves [entry] in the background; saved entries appear in [entries], and their recording is compressed. */
    fun write(entry: HistoryEntry): Job = scope.launch(writer) {
        val dir = File(root, entry.id)
        if (!dir.isDirectory) return@launch // deleted meanwhile
        // A copy from before compression finished (e.g. renamed meanwhile) keeps the compressed file.
        val current = if (entry.audio == RECORDING_FILE && !File(dir, RECORDING_FILE).exists()) entry.copy(audio = COMPRESSED_FILE) else entry
        writeJson(dir, current)
        if (current.saved) {
            publish(current)
            if (current.audio == RECORDING_FILE) compress(current.id)
        }
    }

    /** Moves entries to the trash, from where [restore] brings them back until they are forgotten ([forget]). */
    suspend fun delete(ids: Collection<String>) = withContext(writer) {
        trash.mkdirs()
        for (id in ids) File(root, id).renameTo(File(trash, id))
        _entries.update { list -> list.filterNot { it.id in ids } }
    }

    suspend fun restore(ids: Collection<String>) = withContext(writer) {
        for (id in ids) {
            val dir = File(root, id)
            if (!File(trash, id).renameTo(dir)) continue
            read(dir)?.takeIf { it.saved }?.let(::publish)
        }
    }

    /** Removes deleted entries for good, once their Undo has gone. */
    fun forget(ids: Collection<String>): Job = scope.launch(writer) {
        for (id in ids) File(trash, id).deleteRecursively()
    }

    /** Removes a draft that has nothing worth keeping, without going through the trash. */
    fun discard(id: String): Job = scope.launch(writer) { File(root, id).deleteRecursively() }

    /**
     * Reads every entry. Drafts left by a process that died before Save or Delete are kept as saved
     * if they have text (they can still be deleted from History), and dropped if not, as at Stop.
     */
    private fun load() {
        trash.deleteRecursively()
        val loaded = root.listFiles { file -> file.isDirectory }.orEmpty().mapNotNull { dir ->
            dir.listFiles { file -> file.name.endsWith(".tmp") }?.forEach { it.delete() }
            val entry = read(dir) ?: return@mapNotNull null
            when {
                entry.saved -> entry
                entry.romanian.isEmpty() -> null.also { dir.deleteRecursively() }
                else -> entry.copy(saved = true).also { writeJson(dir, it) }
            }
        }
        _entries.value = loaded.map(::withSize).sortedByDescending { it.startedAt }
        for (entry in loaded) {
            if (entry.audio == RECORDING_FILE) compress(entry.id)
        }
    }

    /**
     * Replaces a saved entry's WAV with an .m4a about an eighth of its size; call on [writer]. If the
     * process dies meanwhile, the WAV stays and is compressed at the next launch.
     */
    private fun compress(id: String) {
        if (!compressing.add(id)) return
        scope.launch(encoder) {
            val dir = File(root, id)
            val wav = File(dir, RECORDING_FILE)
            val temp = File(dir, "$COMPRESSED_FILE.tmp")
            val encoded = try {
                encodeM4a(wav, temp)
                true
            } catch (e: Exception) {
                Log.w(LOG_TAG, "Couldn't compress the recording of $id", e)
                false
            }
            withContext(writer) {
                compressing.remove(id)
                val entry = read(dir) // the latest title; null if deleted meanwhile
                if (!encoded || entry == null || !temp.renameTo(File(dir, COMPRESSED_FILE))) {
                    temp.delete()
                    return@withContext
                }
                val compressed = entry.copy(audio = COMPRESSED_FILE)
                writeJson(dir, compressed)
                wav.delete()
                if (compressed.saved) publish(compressed)
                Log.i(LOG_TAG, "Compressed the recording of $id to ${File(dir, COMPRESSED_FILE).length() / 1000} kB")
            }
        }
    }

    private fun read(dir: File): HistoryEntry? {
        val file = File(dir, ENTRY_FILE)
        if (!file.isFile) return null
        return try {
            HistoryEntry.fromJson(dir.name, JSONObject(file.readText()))
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Unreadable history entry ${dir.name}", e)
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
        val sized = withSize(entry)
        _entries.update { list -> (list.filterNot { it.id == entry.id } + sized).sortedByDescending { it.startedAt } }
    }
}
