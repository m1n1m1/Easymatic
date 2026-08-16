package com.example.ottomatic.feature.grapheditor

import com.example.ottomatic.core.service.LogEntry
import com.example.ottomatic.core.service.LogLevel
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/**
 * The console's timestamps, and the day separators built from them.
 *
 * Pinned because the obvious wrong implementation — arithmetic on the millis —
 * passes every test written in UTC and shows the wrong hour on a real phone.
 */
class ConsoleFormatTest {

    private val utc = ZoneId.of("UTC")

    @Test
    fun `an instant is rendered in the given zone`() {
        assertEquals("00:00:00.000", formatLogTime(0L, ZoneId.of("UTC")))
        assertEquals("01:00:00.000", formatLogTime(0L, ZoneId.of("Europe/Vienna")))
    }

    @Test
    fun `milliseconds are kept`() {
        // Within one run the question is almost always which of two lines came
        // first, and a run finishes in well under a second.
        assertEquals("12:34:56.789", formatLogTime(ms(12, 34, 56, 789), ZoneId.of("UTC")))
    }

    @Test
    fun `a day is the reader's day, not UTC's`() {
        // 23:30 UTC on the 2nd is already the 3rd in Vienna, and a separator that
        // said otherwise would disagree with the clock beside it.
        val lateEvening = day(1) + ms(23, 30, 0, 0)
        assertEquals(LocalDate.of(1970, 1, 2), logDay(lateEvening, utc))
        assertEquals(LocalDate.of(1970, 1, 3), logDay(lateEvening, ZoneId.of("Europe/Vienna")))
    }

    @Test
    fun `a date is written the way the locale writes one`() {
        val date = LocalDate.of(2026, 8, 16)
        assertEquals("16 Aug 2026", formatLogDate(date, Locale.UK))
        assertEquals("Aug 16, 2026", formatLogDate(date, Locale.US))
    }

    @Test
    fun `a row's date is the numeric form, and its clock keeps the millis`() {
        // Narrow enough to sit beside a node name, and still says which day.
        val moment = day(16_663) + ms(12, 34, 56, 789)

        assertEquals("16/08/2015 12:34:56.789", formatLogRowStamp(moment, utc, Locale.UK))
        assertEquals("8/16/15 12:34:56.789", formatLogRowStamp(moment, utc, Locale.US))
    }

    @Test
    fun `the overlay spells the month out where a row abbreviates it`() {
        val moment = day(16_663) + ms(12, 34, 56, 789)

        assertEquals("16 Aug 2015 12:34:56.789", formatLogStamp(moment, utc, Locale.UK))
    }

    @Test
    fun `rows come out newest first`() {
        val rows = consoleRows(listOf(entry(day(1) + 1_000), entry(day(1) + 2_000)), utc)

        assertEquals(
            listOf("00:00:02.000", "00:00:01.000"),
            rows.filterIsInstance<ConsoleRow.Line>().map { formatLogTime(it.entry.atMs, utc) },
        )
    }

    @Test
    fun `one day of lines gets one separator, above its oldest`() {
        val rows = consoleRows(listOf(entry(day(1)), entry(day(1) + 1_000), entry(day(1) + 2_000)), utc)

        // Rendered by a reverseLayout list, so the last row here is the topmost one
        // on screen — which is where a heading belongs.
        assertEquals(4, rows.size)
        assertEquals(1, rows.count { it is ConsoleRow.Day })
        assertEquals(LocalDate.of(1970, 1, 2), (rows.last() as ConsoleRow.Day).date)
    }

    @Test
    fun `each day gets its own separator, directly above that day's oldest line`() {
        val rows = consoleRows(listOf(entry(day(1)), entry(day(2)), entry(day(2) + 1_000)), utc)

        // Newest first: the 3rd's two lines, its heading, the 2nd's line, its heading.
        assertEquals(5, rows.size)
        assertEquals(shape(LINE, LINE, DAY, LINE, DAY), rows.map(::kindOf))
        assertEquals(LocalDate.of(1970, 1, 3), (rows[2] as ConsoleRow.Day).date)
        assertEquals(LocalDate.of(1970, 1, 2), (rows[4] as ConsoleRow.Day).date)
    }

    @Test
    fun `nothing logged is no rows at all`() {
        assertEquals(emptyList<ConsoleRow>(), consoleRows(emptyList(), utc))
    }

    @Test
    fun `every row has its own key`() {
        val rows = consoleRows(listOf(entry(day(1)), entry(day(2)), entry(day(2))), utc)

        assertEquals(rows.size, rows.map { it.key }.toSet().size)
    }

    private fun entry(atMs: Long) = LogEntry(level = LogLevel.INFO, message = "line", atMs = atMs)

    private fun kindOf(row: ConsoleRow): String = if (row is ConsoleRow.Day) DAY else LINE

    private fun shape(vararg kinds: String): List<String> = kinds.toList()

    /** Midnight UTC, [index] days after the epoch. */
    private fun day(index: Int): Long = index * 24L * 3600L * 1000L

    private fun ms(hour: Int, minute: Int, second: Int, millis: Int): Long =
        ((hour * 3600L + minute * 60L + second) * 1000L) + millis

    private companion object {
        const val LINE = "line"
        const val DAY = "day"
    }
}
