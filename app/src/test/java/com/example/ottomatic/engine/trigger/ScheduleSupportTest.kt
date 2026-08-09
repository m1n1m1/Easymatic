package com.example.ottomatic.engine.trigger

import com.example.ottomatic.domain.model.MINUTES_PER_DAY
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * Verifies the wall-clock logic behind `trigger.schedule` — the day filters and
 * the [ScheduleWindow] shared by every mode, and the next-fire-time search that
 * decides when the alarm-driven paths arm their exact alarms.
 *
 * Timestamps are built through [Calendar] so the assertions read in the device's
 * own timezone, which is the timezone the production code works in.
 */
class ScheduleSupportTest {

    @Test
    fun `matches accepts every day and time when no filter is configured`() {
        val config = ScheduleConfig()
        assertTrue(config.matches(at(2026, Calendar.JULY, 25, 3, 15)))
        assertTrue(config.matches(at(2026, Calendar.JULY, 26, 23, 59)))
    }

    @Test
    fun `day of week filter keeps only the selected days`() {
        // 2026-07-27 is a Monday, 2026-07-28 a Tuesday.
        val config = ScheduleConfig(monday = true)
        assertTrue(config.matchesDay(at(2026, Calendar.JULY, 27, 12, 0)))
        assertFalse(config.matchesDay(at(2026, Calendar.JULY, 28, 12, 0)))
    }

    @Test
    fun `day of month filter keeps only the listed days`() {
        val config = ScheduleConfig(daysOfMonth = "1, 15")
        assertTrue(config.matchesDay(at(2026, Calendar.JULY, 15, 9, 0)))
        assertFalse(config.matchesDay(at(2026, Calendar.JULY, 16, 9, 0)))
    }

    /**
     * `minutesOfDay` now reads through the one `TimeOfDay` parser the config form's
     * clock face writes with, so an out-of-range time is clamped into a real day
     * rather than arriving as a minute count no clock could show — which used to
     * make a window built on it silently unsatisfiable.
     */
    @Test
    fun `an out-of-range time is clamped into a real day`() {
        assertEquals(23 * 60 + 59, minutesOfDay("25:99"))
        assertEquals(0, minutesOfDay("nonsense"))
        assertEquals(0, minutesOfDay(""))
        assertEquals(7 * 60, minutesOfDay("7"))
    }

    @Test
    fun `a window contains the minutes between its ends, the start included`() {
        val window = ScheduleWindow(minutesOfDay("09:00"), minutesOfDay("17:00"))
        assertFalse(window.wraps)
        assertEquals(8 * 60, window.lengthMinutes)
        assertTrue(window.contains(minutesOfDay("09:00")))
        assertTrue(window.contains(minutesOfDay("16:59")))
        assertFalse(window.contains(minutesOfDay("08:59")))
        assertFalse(window.contains(minutesOfDay("17:00")))
    }

    @Test
    fun `a window whose end precedes its start wraps past midnight`() {
        val window = ScheduleWindow(minutesOfDay("23:00"), minutesOfDay("01:00"))
        assertTrue(window.wraps)
        assertEquals(2 * 60, window.lengthMinutes)
        assertTrue(window.contains(minutesOfDay("23:00")))
        assertTrue(window.contains(minutesOfDay("00:30")))
        assertFalse(window.contains(minutesOfDay("01:00")))
        assertFalse(window.contains(minutesOfDay("12:00")))
    }

    @Test
    fun `a zero length window covers the whole day rather than nothing`() {
        val window = ScheduleWindow(minutesOfDay("06:00"), minutesOfDay("06:00"))
        assertEquals(MINUTES_PER_DAY, window.lengthMinutes)
        assertTrue(window.contains(minutesOfDay("05:59")))
        assertTrue(window.contains(minutesOfDay("06:00")))
    }

    @Test
    fun `a wrapping window anchors its post-midnight tail to the day it opened on`() {
        val window = ScheduleWindow(minutesOfDay("23:00"), minutesOfDay("01:00"))
        // Sunday 00:30 belongs to the window that opened on Saturday the 25th.
        assertEquals(
            at(2026, Calendar.JULY, 25, 0, 30),
            window.anchorDay(at(2026, Calendar.JULY, 26, 0, 30)),
        )
        // Anything at or after the start is already on the opening day.
        val evening = at(2026, Calendar.JULY, 25, 23, 30)
        assertEquals(evening, window.anchorDay(evening))
    }

    @Test
    fun `a window that does not wrap never shifts the day`() {
        val window = ScheduleWindow(minutesOfDay("09:00"), minutesOfDay("17:00"))
        val morning = at(2026, Calendar.JULY, 25, 8, 0)
        assertEquals(morning, window.anchorDay(morning))
    }

    @Test
    fun `an overnight window on a selected day is not truncated at midnight`() {
        // 2026-07-25 is a Saturday, 2026-07-26 a Sunday. "Saturdays, 23:00-01:00"
        // has to mean one continuous stretch into Sunday morning, or the day
        // filter silently halves every overnight window.
        val config = ScheduleConfig(
            windowEnabled = true,
            windowFrom = "23:00",
            windowUntil = "01:00",
            saturday = true,
        )
        assertTrue(config.matches(at(2026, Calendar.JULY, 25, 23, 30)))
        assertTrue(config.matches(at(2026, Calendar.JULY, 26, 0, 30)))
        assertFalse("Sunday night belongs to Sunday's window, which is not selected",
            config.matches(at(2026, Calendar.JULY, 26, 23, 30)))
        assertFalse("outside the window entirely", config.matches(at(2026, Calendar.JULY, 25, 12, 0)))
    }

    @Test
    fun `the window is only active when the toggle is on and the mode is interval`() {
        val fields = ScheduleConfig(windowFrom = "23:00", windowUntil = "01:00")
        assertNull("the toggle is off", fields.window)
        assertNotNull(fields.copy(windowEnabled = true).window)
        assertNull(
            "a toggle left over from interval mode must not restrict a daily time",
            fields.copy(windowEnabled = true, mode = ScheduleMode.AT_TIME).window,
        )
    }

    @Test
    fun `an inactive window leaves the day filters as the only restriction`() {
        val config = ScheduleConfig(windowFrom = "23:00", windowUntil = "01:00", saturday = true)
        assertTrue(config.matches(at(2026, Calendar.JULY, 25, 12, 0)))
        assertFalse(config.matches(at(2026, Calendar.JULY, 26, 12, 0)))
    }

    @Test
    fun `interval minutes combine the amount and the unit`() {
        assertEquals(15L, ScheduleConfig().intervalMinutes)
        assertEquals(90L, ScheduleConfig(every = 90, everyUnit = IntervalUnit.MINUTES).intervalMinutes)
        assertEquals(120L, ScheduleConfig(every = 2, everyUnit = IntervalUnit.HOURS).intervalMinutes)
        assertEquals(2_880L, ScheduleConfig(every = 2, everyUnit = IntervalUnit.DAYS).intervalMinutes)
    }

    @Test
    fun `a non-positive interval is clamped to one minute rather than dividing by zero`() {
        assertEquals(1L, ScheduleConfig(every = 0).intervalMinutes)
    }

    @Test
    fun `only sub-15-minute cadences fall back to the minute tick broadcast`() {
        assertTrue(ScheduleConfig(every = 1).usesMinuteTicks)
        assertTrue(ScheduleConfig(every = 14).usesMinuteTicks)
        assertFalse(ScheduleConfig(every = 15).usesMinuteTicks)
        assertFalse(ScheduleConfig(every = 1, everyUnit = IntervalUnit.HOURS).usesMinuteTicks)
    }

    @Test
    fun `a daily time is later the same day when it is still ahead`() {
        val now = at(2026, Calendar.JULY, 25, 6, 0)
        assertEquals(at(2026, Calendar.JULY, 25, 7, 30), nextFireTime(daily("07:30"), now))
    }

    @Test
    fun `a daily time rolls to tomorrow once today's has passed`() {
        val now = at(2026, Calendar.JULY, 25, 8, 0)
        assertEquals(at(2026, Calendar.JULY, 26, 7, 30), nextFireTime(daily("07:30"), now))
    }

    @Test
    fun `a daily time skips forward to the next selected weekday`() {
        // From Saturday 2026-07-25, the next Monday is 2026-07-27.
        val now = at(2026, Calendar.JULY, 25, 8, 0)
        val config = daily("07:30").copy(monday = true)
        assertEquals(at(2026, Calendar.JULY, 27, 7, 30), nextFireTime(config, now))
    }

    @Test
    fun `a daily time honours a day of month filter across a month boundary`() {
        val now = at(2026, Calendar.JULY, 25, 8, 0)
        val config = daily("07:30").copy(daysOfMonth = "1")
        assertEquals(at(2026, Calendar.AUGUST, 1, 7, 30), nextFireTime(config, now))
    }

    @Test
    fun `a filter combination no day can satisfy yields no fire time`() {
        // No month has a 32nd, so this can never be satisfied and the search has
        // to give up rather than scan forever.
        val config = daily("07:30").copy(daysOfMonth = "32")
        assertNull(nextFireTime(config, at(2026, Calendar.JULY, 25, 8, 0)))
    }

    @Test
    fun `windowed interval slots are aligned to the window start`() {
        val config = windowed(every = 30, from = "23:00", until = "01:00")
        assertEquals(
            at(2026, Calendar.JULY, 25, 23, 0),
            nextFireTime(config, at(2026, Calendar.JULY, 25, 20, 0)),
        )
        assertEquals(
            at(2026, Calendar.JULY, 25, 23, 30),
            nextFireTime(config, at(2026, Calendar.JULY, 25, 23, 0)),
        )
        assertEquals(
            at(2026, Calendar.JULY, 26, 0, 30),
            nextFireTime(config, at(2026, Calendar.JULY, 26, 0, 0)),
        )
    }

    @Test
    fun `the slot after the last one in a window opens the next day's window`() {
        val config = windowed(every = 30, from = "23:00", until = "01:00")
        // 00:30 is the last slot; 01:00 is past the window's end.
        assertEquals(
            at(2026, Calendar.JULY, 26, 23, 0),
            nextFireTime(config, at(2026, Calendar.JULY, 26, 0, 30)),
        )
    }

    @Test
    fun `a windowed interval skips days its day filter rejects`() {
        // Saturdays only: from Sunday the 26th, the next window opens on the 1st
        // of August, the following Saturday.
        val config = windowed(every = 30, from = "23:00", until = "01:00").copy(saturday = true)
        assertEquals(
            at(2026, Calendar.AUGUST, 1, 23, 0),
            nextFireTime(config, at(2026, Calendar.JULY, 26, 12, 0)),
        )
    }

    @Test
    fun `a windowed interval still finds the current window's remaining slots`() {
        // Saturdays only, standing inside Sunday's post-midnight tail: the tail
        // belongs to Saturday's window, so 00:30 is still due.
        val config = windowed(every = 30, from = "23:00", until = "01:00").copy(saturday = true)
        assertEquals(
            at(2026, Calendar.JULY, 26, 0, 30),
            nextFireTime(config, at(2026, Calendar.JULY, 26, 0, 15)),
        )
    }

    @Test
    fun `an interval longer than its window fires once per opening`() {
        val config = windowed(every = 3, from = "23:00", until = "01:00")
            .copy(everyUnit = IntervalUnit.HOURS)
        assertEquals(
            at(2026, Calendar.JULY, 26, 23, 0),
            nextFireTime(config, at(2026, Calendar.JULY, 25, 23, 30)),
        )
    }

    private fun daily(atTime: String) = ScheduleConfig(mode = ScheduleMode.AT_TIME, atTime = atTime)

    private fun windowed(every: Int, from: String, until: String) = ScheduleConfig(
        every = every,
        windowEnabled = true,
        windowFrom = from,
        windowUntil = until,
    )

    @Test
    fun `an unparseable time is read as midnight rather than throwing`() {
        assertEquals(0, minutesOfDay("not a time"))
        assertEquals(7 * 60 + 30, minutesOfDay("07:30"))
        assertEquals(23 * 60 + 5, minutesOfDay("23:05"))
    }

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        Calendar.getInstance().apply {
            set(year, month, day, hour, minute, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
}
