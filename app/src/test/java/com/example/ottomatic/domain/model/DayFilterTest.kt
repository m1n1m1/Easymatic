package com.example.ottomatic.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * The day arithmetic `trigger.schedule` and `action.wait_until` share.
 *
 * Timestamps are built through [Calendar] so the assertions read in the device's
 * own timezone, which is the timezone the production code works in.
 */
class DayFilterTest {

    @Test
    fun `an empty filter accepts every day`() {
        val filter = DayFilter.of()
        assertTrue(filter.matches(at(2026, Calendar.JULY, 25)))
        assertTrue(filter.matches(at(2026, Calendar.JULY, 26)))
    }

    @Test
    fun `a weekday filter keeps only the days named`() {
        // 2026-07-27 is a Monday, 2026-07-28 a Tuesday.
        val filter = DayFilter.of(monday = true)
        assertTrue(filter.matches(at(2026, Calendar.JULY, 27)))
        assertFalse(filter.matches(at(2026, Calendar.JULY, 28)))
    }

    @Test
    fun `a day-of-month list is read from the form's own spelling`() {
        assertEquals(setOf(1, 15), DayFilter.monthDaysOf(" 1 , 15 "))
        assertEquals(emptySet<Int>(), DayFilter.monthDaysOf(""))
        // Junk is dropped rather than failing the whole field.
        assertEquals(setOf(3), DayFilter.monthDaysOf("3,tuesday"))
    }

    /** Both filters apply, so a weekday *and* a date is the intersection of the two. */
    @Test
    fun `the two filters combine with and`() {
        val filter = DayFilter.of(monday = true, daysOfMonth = "27")
        assertTrue(filter.matches(at(2026, Calendar.JULY, 27)))
        // A Monday, but not the 27th.
        assertFalse(filter.matches(at(2026, Calendar.AUGUST, 3)))
        // The 27th, but not a Monday.
        assertFalse(filter.matches(at(2026, Calendar.AUGUST, 27)))
    }

    @Test
    fun `the next occurrence is later the same day when it is still ahead`() {
        val now = at(2026, Calendar.JULY, 27, hour = 6)
        val next = nextTimeOfDay(7 * 60 + 30, DayFilter.of(), now)!!
        assertEquals(at(2026, Calendar.JULY, 27, hour = 7, minute = 30), next)
    }

    @Test
    fun `the next occurrence rolls to tomorrow once today's has passed`() {
        val now = at(2026, Calendar.JULY, 27, hour = 9)
        val next = nextTimeOfDay(7 * 60 + 30, DayFilter.of(), now)!!
        assertEquals(at(2026, Calendar.JULY, 28, hour = 7, minute = 30), next)
    }

    @Test
    fun `the next occurrence skips forward to the next selected day`() {
        // Monday the 27th, 09:00 — the next Friday is the 31st.
        val now = at(2026, Calendar.JULY, 27, hour = 9)
        val next = nextTimeOfDay(7 * 60 + 30, DayFilter.of(friday = true), now)!!
        assertEquals(at(2026, Calendar.JULY, 31, hour = 7, minute = 30), next)
    }

    /**
     * A filter no day can satisfy answers null rather than scanning forever —
     * which is what lets a caller arm nothing instead of hanging.
     */
    @Test
    fun `a filter nothing satisfies yields no moment at all`() {
        val impossible = DayFilter.of(monday = true, daysOfMonth = "32")
        assertNull(nextTimeOfDay(7 * 60, impossible, at(2026, Calendar.JULY, 27)))
    }

    private fun at(year: Int, month: Int, day: Int, hour: Int = 0, minute: Int = 0): Long =
        Calendar.getInstance().apply {
            set(year, month, day, hour, minute, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
}
