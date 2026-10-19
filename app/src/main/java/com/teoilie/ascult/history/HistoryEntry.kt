package com.teoilie.ascult.history

import com.teoilie.ascult.settings.MicSource
import com.teoilie.ascult.settings.Translator
import com.teoilie.ascult.soniox.ClaudeText
import com.teoilie.ascult.soniox.Segment
import com.teoilie.ascult.soniox.Transcript
import com.teoilie.ascult.soniox.text
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToLong

/**
 * One Start→Stop run kept in History: its text, the audio settings it used and its recording.
 * [id] names its folder; [saved] is false while it is a draft (listening, or waiting for Save/Delete).
 */
data class HistoryEntry(
    val id: String,
    val title: String,
    val startedAt: Long,
    val endedAt: Long = startedAt,
    val source: MicSource,
    val gainDb: Int,
    val translator: Translator,
    val romanian: String = "",
    val english: String = "",
    val claude: ClaudeText? = null,
    /** File name of the recording in the entry's folder, if recording was on. */
    val audio: String? = null,
    val saved: Boolean = false,
    /** Size of the recording; read from the file, not stored. */
    val audioBytes: Long = 0,
) {
    /** The text as a segment, for the panes and for sharing. */
    fun segment(zone: ZoneId = ZoneId.systemDefault()) =
        Segment(details(zone), Transcript(romanian), Transcript(english), claude)

    /** The run's [segment] text as it stands at [endedAt]. */
    fun withText(segment: Segment, endedAt: Long) = copy(
        endedAt = endedAt,
        romanian = segment.romanian.text(),
        english = segment.english.text(),
        claude = segment.claude?.copy(translatedUpTo = segment.romanian.text().length),
    )

    /** Rounded to whole minutes, at least one. */
    val minutes: Long get() = ((endedAt - startedAt) / 60_000.0).roundToLong().coerceAtLeast(1)

    /** e.g. "10:15–10:57 · Voice recognition · +12 dB · Claude" */
    fun details(zone: ZoneId = ZoneId.systemDefault()): String =
        "${clock(startedAt, zone)}–${clock(endedAt, zone)} · ${source.label} · +$gainDb dB · ${translator.label}"

    /** e.g. "10:15 · 42 min · Claude" */
    fun summary(zone: ZoneId = ZoneId.systemDefault()): String = "${clock(startedAt, zone)} · $minutes min · ${translator.label}"

    /** The English to show: Claude's unless it gave up, else Soniox's. */
    val shownEnglish: String get() = if (claude != null && !claude.failed) claude.english else english

    fun toJson(): JSONObject = JSONObject()
        .put("title", title)
        .put("saved", saved)
        .put("startedAt", startedAt)
        .put("endedAt", endedAt)
        .put("source", source.name)
        .put("gainDb", gainDb)
        .put("translator", translator.name)
        .put("romanian", romanian)
        .put("english", english)
        .put("claude", claude?.let { JSONObject().put("english", it.english).put("failed", it.failed) } ?: JSONObject.NULL)
        .put("audio", audio ?: JSONObject.NULL)

    companion object {
        fun fromJson(id: String, json: JSONObject): HistoryEntry {
            val romanian = json.optString("romanian")
            return HistoryEntry(
                id = id,
                title = json.getString("title"),
                saved = json.optBoolean("saved"),
                startedAt = json.getLong("startedAt"),
                endedAt = json.getLong("endedAt"),
                source = enumOr(json.optString("source"), MicSource.VOICE_RECOGNITION),
                gainDb = json.optInt("gainDb"),
                translator = enumOr(json.optString("translator"), Translator.SONIOX),
                romanian = romanian,
                english = json.optString("english"),
                claude = json.optJSONObject("claude")?.let {
                    ClaudeText(it.optString("english"), translatedUpTo = romanian.length, failed = it.optBoolean("failed"))
                },
                audio = if (json.isNull("audio")) null else json.optString("audio"),
            )
        }
    }
}

/** A new entry's name, e.g. "Sunday 18 Oct". */
fun defaultTitle(startedAt: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    DateTimeFormatter.ofPattern("EEEE d MMM", Locale.ENGLISH).format(Instant.ofEpochMilli(startedAt).atZone(zone))

private fun clock(time: Long, zone: ZoneId): String =
    DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH).format(Instant.ofEpochMilli(time).atZone(zone))

private inline fun <reified T : Enum<T>> enumOr(name: String, default: T): T =
    enumValues<T>().firstOrNull { it.name == name } ?: default
