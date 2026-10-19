package com.teoilie.ascult.history

import com.teoilie.ascult.settings.MicSource
import com.teoilie.ascult.settings.Translator
import com.teoilie.ascult.soniox.ClaudeText
import com.teoilie.ascult.soniox.Segment
import com.teoilie.ascult.soniox.Transcript
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneOffset
import java.time.ZonedDateTime

class HistoryEntryTest {
    private val utc = ZoneOffset.UTC
    private val start = ZonedDateTime.of(2026, 10, 18, 10, 15, 0, 0, utc).toInstant().toEpochMilli()

    private val entry = HistoryEntry(
        id = "20261018-101500",
        title = "Sunday 18 Oct",
        startedAt = start,
        endedAt = start + 42 * 60_000L + 20_000L,
        source = MicSource.CAMCORDER,
        gainDb = 12,
        translator = Translator.CLAUDE,
        romanian = "Bună ziua.",
        english = "Good day.",
        claude = ClaudeText("Good afternoon.", translatedUpTo = 10, failed = false),
        audio = "audio.m4a",
        saved = true,
    )

    @Test
    fun `json round trip keeps everything but the file size`() {
        val json = JSONObject(entry.copy(audioBytes = 123).toJson().toString())
        assertEquals(entry, HistoryEntry.fromJson(entry.id, json))
    }

    @Test
    fun `json round trip without Claude or audio`() {
        val plain = entry.copy(translator = Translator.SONIOX, claude = null, audio = null, saved = false)
        val read = HistoryEntry.fromJson(plain.id, JSONObject(plain.toJson().toString()))
        assertEquals(plain, read)
        assertNull(read.audio)
    }

    @Test
    fun `unknown enum names fall back to defaults`() {
        val json = entry.toJson().put("source", "GONE").put("translator", "GONE")
        val read = HistoryEntry.fromJson(entry.id, json)
        assertEquals(MicSource.VOICE_RECOGNITION, read.source)
        assertEquals(Translator.SONIOX, read.translator)
    }

    @Test
    fun `withText keeps final and provisional text and the end time`() {
        val segment = Segment(
            "label",
            romanian = Transcript("Bună", " ziua "),
            english = Transcript("Good", " day"),
            claude = ClaudeText("Hello", translatedUpTo = 4),
        )
        val updated = entry.withText(segment, endedAt = start + 60_000)
        assertEquals("Bună ziua", updated.romanian)
        assertEquals("Good day", updated.english)
        assertEquals("Hello", updated.claude?.english)
        assertEquals(start + 60_000, updated.endedAt)
    }

    @Test
    fun `labels`() {
        assertEquals("Sunday 18 Oct", defaultTitle(start, utc))
        assertEquals("10:15–10:57 · Camcorder · +12 dB · Claude", entry.details(utc))
        assertEquals("10:15 · 42 min · Claude", entry.summary(utc))
        assertEquals(1, entry.copy(endedAt = start + 5_000).minutes)
    }

    @Test
    fun `shown English is Claude's unless it failed`() {
        assertEquals("Good afternoon.", entry.shownEnglish)
        assertEquals("Good day.", entry.copy(claude = entry.claude?.copy(failed = true)).shownEnglish)
        assertEquals("Good day.", entry.copy(claude = null).shownEnglish)
    }
}
