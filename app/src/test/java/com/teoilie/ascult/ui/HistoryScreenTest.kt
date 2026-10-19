package com.teoilie.ascult.ui

import com.teoilie.ascult.history.HistoryEntry
import com.teoilie.ascult.settings.MicSource
import com.teoilie.ascult.settings.Translator
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneOffset
import java.time.ZonedDateTime

class HistoryScreenTest {
    private val utc = ZoneOffset.UTC

    private fun at(year: Int, month: Int, day: Int) =
        ZonedDateTime.of(year, month, day, 10, 15, 0, 0, utc).toInstant().toEpochMilli()

    private fun entry(id: String, startedAt: Long) =
        HistoryEntry(id, id, startedAt, source = MicSource.MIC, gainDb = 0, translator = Translator.SONIOX)

    @Test
    fun `groups this week, then months, with the year only for other years`() {
        val now = at(2026, 10, 18) // a Sunday
        val entries = listOf(
            entry("sun18", at(2026, 10, 18)),
            entry("mon12", at(2026, 10, 12)), // Monday: still this week
            entry("sun11", at(2026, 10, 11)),
            entry("oct1", at(2026, 10, 1)),
            entry("sep14", at(2026, 9, 14)),
            entry("dec25", at(2025, 12, 25)),
        )
        val groups = historyGroups(entries, now, utc).map { (name, items) -> name to items.map { it.id } }
        assertEquals(
            listOf(
                "This week" to listOf("sun18", "mon12"),
                "October" to listOf("sun11", "oct1"),
                "September" to listOf("sep14"),
                "December 2025" to listOf("dec25"),
            ),
            groups,
        )
    }

    @Test
    fun `player labels`() {
        assertEquals("0:00", clockTime(0))
        assertEquals("4:05", clockTime(245_900))
        assertEquals("1:02:09", clockTime(3_729_000))
        assertEquals("1×", speedLabel(1f))
        assertEquals("1.25×", speedLabel(1.25f))
        assertEquals("0.75×", speedLabel(0.75f))
        assertEquals(81, megabytes(80_900_000))
    }
}
