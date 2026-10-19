package com.teoilie.ascult.history

import com.teoilie.ascult.settings.AudioSettings
import com.teoilie.ascult.settings.MicSource
import com.teoilie.ascult.settings.Translator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.ZoneOffset

class HistoryStoreTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val root by lazy { folder.newFolder("history") }

    @After
    fun tearDown() = scope.cancel()

    private fun entry(id: String, saved: Boolean, romanian: String = "Bună ziua.") = HistoryEntry(
        id = id,
        title = id,
        startedAt = id.filter(Char::isDigit).toLong(),
        source = MicSource.MIC,
        gainDb = 0,
        translator = Translator.SONIOX,
        romanian = romanian,
        saved = saved,
    )

    /** Puts [entry] on disk as a previous run of the app left it. */
    private fun onDisk(entry: HistoryEntry) {
        File(root, entry.id).apply { mkdirs() }.resolve("entry.json").writeText(entry.toJson().toString())
    }

    private fun loadedStore() = HistoryStore(root, scope).also { runBlocking { it.loading.join() } }

    private fun savedFlag(id: String) = JSONObject(File(root, "$id/entry.json").readText()).getBoolean("saved")

    @Test
    fun `load keeps saved entries, saves drafts with text and drops empty ones`() {
        onDisk(entry("20261011-101500", saved = true))
        onDisk(entry("20261018-101500", saved = false))
        onDisk(entry("20261018-111500", saved = false, romanian = ""))
        File(root, ".trash/20261001-090000").mkdirs()
        File(root, "20261011-101500/audio.m4a.tmp").writeText("half")

        val store = loadedStore()

        assertEquals(listOf("20261018-101500", "20261011-101500"), store.entries.value.map { it.id })
        assertTrue(savedFlag("20261018-101500"))
        assertFalse(File(root, "20261018-111500").exists())
        assertFalse(File(root, ".trash").exists())
        assertFalse(File(root, "20261011-101500/audio.m4a.tmp").exists())
    }

    @Test
    fun `only saved entries are listed`() = runBlocking {
        val store = loadedStore()
        val draft = store.create(AudioSettings(), startedAt = 0, zone = ZoneOffset.UTC).copy(romanian = "Bună")
        store.write(draft).join()
        assertTrue(store.entries.value.isEmpty())
        assertFalse(savedFlag(draft.id))

        store.write(draft.copy(saved = true)).join()
        assertEquals(listOf(draft.id), store.entries.value.map { it.id })
    }

    @Test
    fun `deleted entries can be restored until forgotten`() = runBlocking {
        onDisk(entry("20261011-101500", saved = true))
        onDisk(entry("20261018-101500", saved = true))
        val store = loadedStore()

        store.delete(listOf("20261011-101500"))
        assertEquals(listOf("20261018-101500"), store.entries.value.map { it.id })
        store.restore(listOf("20261011-101500"))
        assertEquals(listOf("20261018-101500", "20261011-101500"), store.entries.value.map { it.id })

        store.delete(listOf("20261011-101500", "20261018-101500"))
        store.forget(listOf("20261011-101500")).join()
        store.restore(listOf("20261011-101500", "20261018-101500"))
        assertEquals(listOf("20261018-101500"), store.entries.value.map { it.id })
        assertFalse(File(root, "20261011-101500").exists())
    }

    @Test
    fun `runs started in the same second get their own folders`() {
        val store = loadedStore()
        val settings = AudioSettings(saveRecording = true)
        val first = store.create(settings, startedAt = 0, zone = ZoneOffset.UTC)
        val second = store.create(settings, startedAt = 0, zone = ZoneOffset.UTC)
        assertEquals("19700101-000000", first.id)
        assertNotEquals(first.id, second.id)
        assertEquals(File(root, "${second.id}/audio.wav"), store.recordingFile(second))
    }
}
