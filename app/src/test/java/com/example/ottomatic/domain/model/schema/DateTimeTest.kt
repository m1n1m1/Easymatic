package com.example.ottomatic.domain.model.schema

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * The date primitive, whose whole contract is its text: [DateTime.toString] is what
 * every consumer sees (a notification, a template, a comparison) and
 * [DateTime.parse] is what every producer writes back. If those two ever disagree,
 * a value stops surviving a round trip through the graph.
 */
class DateTimeTest {

    @Test
    fun `the text form round-trips exactly`() {
        listOf(0L, 1_753_617_791_000L, 1_753_617_791_123L, -86_400_000L).forEach { millis ->
            val date = DateTime(millis)
            assertEquals(date.toString(), millis, DateTime.parse(date.toString())?.epochMs)
        }
    }

    @Test
    fun `the text form is ISO-8601 with an offset`() {
        val text = DateTime(0).toString()
        assertTrue(text, text.matches(Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(:\d{2}(\.\d+)?)?(Z|[+-]\d{2}:\d{2})""")))
    }

    @Test
    fun `a long integer reads as epoch milliseconds`() {
        assertEquals(1_753_617_791_000L, DateTime.parse("1753617791000")?.epochMs)
        assertEquals(-1_753_617_791_000L, DateTime.parse("-1753617791000")?.epochMs)
    }

    @Test
    fun `a short integer reads as epoch seconds`() {
        // What a web API usually means, and the reason `json_read` can feed a date
        // port without the user doing arithmetic.
        assertEquals(1_753_617_791_000L, DateTime.parse("1753617791")?.epochMs)
        assertEquals(0L, DateTime.parse("0")?.epochMs)
    }

    @Test
    fun `an offset in the text wins over the device timezone`() {
        assertEquals(0L, DateTime.parse("1970-01-01T00:00:00Z")?.epochMs)
        assertEquals(0L, DateTime.parse("1970-01-01T02:00:00+02:00")?.epochMs)
    }

    @Test
    fun `a local date-time resolves against the device timezone`() {
        val expected = LocalDate.of(2026, 7, 27).atTime(14, 3)
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        listOf("2026-07-27T14:03", "2026-07-27T14:03:00", "2026-07-27 14:03", "2026-07-27 14:03:00")
            .forEach { assertEquals(it, expected, DateTime.parse(it)?.epochMs) }
    }

    @Test
    fun `a bare date is that day's midnight`() {
        val expected = LocalDate.of(2026, 7, 27).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        assertEquals(expected, DateTime.parse("2026-07-27")?.epochMs)
    }

    @Test
    fun `a bare time is today at that time`() {
        // This is what makes "after 18:00" expressible: the same stored text means
        // six this evening, and six tomorrow evening tomorrow.
        val zone = ZoneId.systemDefault()
        val expected = LocalTime.of(18, 0).atDate(LocalDate.now(zone)).atZone(zone).toInstant().toEpochMilli()
        assertEquals(expected, DateTime.parse("18:00")?.epochMs)
    }

    @Test
    fun `text that is not a moment is not one`() {
        listOf(null, "", "   ", "not a date", "2026-13-45", "abc123", "12:99").forEach {
            assertNull(it, DateTime.parse(it))
        }
    }

    @Test
    fun `dates order by the moment they name, not by their text`() {
        val earlier = DateTime.parse("2026-07-27T23:00:00+02:00")!!
        val later = DateTime.parse("2026-07-27T22:00:00Z")!!
        // 21:00Z against 22:00Z — the text compares the other way round, which is
        // exactly the trap `Comparable` exists to avoid here.
        assertTrue(earlier < later)
    }
}
