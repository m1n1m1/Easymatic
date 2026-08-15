package com.example.ottomatic.engine.action

import com.example.ottomatic.domain.model.schema.DateTime
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The stretch of time `action.calendar_query` looks at.
 *
 * Written with an explicit zone rather than the system default, for the reason
 * `EventTimesTest` is: "today" is a local idea, and a test that passes only in the
 * developer's timezone is the shape of the bug.
 */
class CalendarQueryWindowTest {

    private val vienna = ZoneId.of("Europe/Vienna")
    private val tuesdayMorning = Instant.parse("2026-08-04T07:30:00+02:00").toEpochMilli()

    /**
     * The end of the *local* day, not a rolling twenty-four hours. Asked at half past
     * seven, "today" must not include tomorrow morning's stand-up.
     */
    @Test
    fun `today runs from now to the end of the local day`() {
        val (from, until) = config(CalendarWindow.TODAY).windowAt(tuesdayMorning, vienna)

        assertEquals(tuesdayMorning, from)
        assertEquals(Instant.parse("2026-08-05T00:00:00+02:00").toEpochMilli(), until)
    }

    /**
     * It starts at *now* and not at midnight, deliberately: an appointment that finished
     * an hour ago is not something a macro is about to act on.
     */
    @Test
    fun `today late in the evening is a short window rather than an empty one`() {
        val lateEvening = Instant.parse("2026-08-04T23:40:00+02:00").toEpochMilli()

        val (from, until) = config(CalendarWindow.TODAY).windowAt(lateEvening, vienna)

        assertEquals(lateEvening, from)
        assertTrue(until > from)
        assertEquals(20L * 60 * 1000, until - from)
    }

    @Test
    fun `the next few days runs from now`() {
        val (from, until) = config(CalendarWindow.NEXT_DAYS, days = 3).windowAt(tuesdayMorning, vienna)

        assertEquals(tuesdayMorning, from)
        assertEquals(tuesdayMorning + 3L * 24 * 60 * 60 * 1000, until)
    }

    /** Zero days is not a window anybody means; it is clamped rather than answering nothing. */
    @Test
    fun `zero days is clamped to one`() {
        val (from, until) = config(CalendarWindow.NEXT_DAYS, days = 0).windowAt(tuesdayMorning, vienna)

        assertEquals(24L * 60 * 60 * 1000, until - from)
    }

    @Test
    fun `between uses the two times as given`() {
        val start = Instant.parse("2026-09-01T00:00:00Z").toEpochMilli()
        val end = Instant.parse("2026-09-08T00:00:00Z").toEpochMilli()

        val (from, until) = config(CalendarWindow.BETWEEN)
            .copy(from = DateTime(start), until = DateTime(end))
            .windowAt(tuesdayMorning, vienna)

        assertEquals(start, from)
        assertEquals(end, until)
    }

    /**
     * Left unset, "between" is an empty window rather than the whole of history — which
     * the node reports rather than issuing a query nobody meant.
     */
    @Test
    fun `between with nothing filled in is an empty window`() {
        val (from, until) = config(CalendarWindow.BETWEEN).windowAt(tuesdayMorning, vienna)

        assertTrue(until <= from)
    }

    private fun config(window: CalendarWindow, days: Int = 7) =
        CalendarQueryConfig(window = window, days = days)
}
