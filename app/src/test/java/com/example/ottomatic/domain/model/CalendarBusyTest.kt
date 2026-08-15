package com.example.ottomatic.domain.model

import com.example.ottomatic.core.service.CalendarAvailability
import com.example.ottomatic.core.service.CalendarEventRecord
import com.example.ottomatic.core.service.CalendarEventStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What counts as an appointment, and what counts as being busy.
 *
 * The cancelled and declined cases are the ones that break the feature against itself:
 * deleting one occurrence of a series leaves a tombstone behind, so a reader that does not
 * skip it goes on finding the appointment this app has just deleted.
 */
class CalendarBusyTest {

    private val nineToTen = event(start = 9.h, end = 10.h)

    @Test
    fun `an ordinary confirmed appointment is live and busy`() {
        assertTrue(CalendarBusy.isLive(nineToTen))
        assertTrue(CalendarBusy.isBusy(nineToTen))
    }

    /** The tombstone `action.calendar_update`'s delete-one path leaves behind. */
    @Test
    fun `a cancelled occurrence is neither live nor busy`() {
        val cancelled = nineToTen.copy(status = CalendarEventStatus.CANCELLED)

        assertFalse(CalendarBusy.isLive(cancelled))
        assertFalse(CalendarBusy.isBusy(cancelled))
    }

    @Test
    fun `an invitation this user declined is neither live nor busy`() {
        val declined = nineToTen.copy(declined = true)

        assertFalse(CalendarBusy.isLive(declined))
        assertFalse(CalendarBusy.isBusy(declined))
    }

    /** A reminder to take the bins out exists, and blocks nothing. */
    @Test
    fun `an appointment marked free is live but not busy`() {
        val free = nineToTen.copy(availability = CalendarAvailability.FREE)

        assertTrue(CalendarBusy.isLive(free))
        assertFalse(CalendarBusy.isBusy(free))
    }

    /**
     * The judgement the class KDoc defends: a birthday or holiday feed would otherwise
     * make "am I busy?" answer yes on most days of the year.
     */
    @Test
    fun `an all-day appointment is live but not busy`() {
        val holiday = nineToTen.copy(allDay = true, startEpochMs = 0.h, endEpochMs = 24.h)

        assertTrue(CalendarBusy.isLive(holiday))
        assertFalse(CalendarBusy.isBusy(holiday))
    }

    @Test
    fun `a tentative appointment still counts as busy`() {
        assertTrue(CalendarBusy.isBusy(nineToTen.copy(availability = CalendarAvailability.TENTATIVE)))
    }

    @Test
    fun `the start counts and the end does not`() {
        assertTrue(CalendarBusy.isOnAt(nineToTen, 9.h))
        assertTrue(CalendarBusy.isOnAt(nineToTen, 9.h + 1))
        assertFalse(CalendarBusy.isOnAt(nineToTen, 10.h))
        assertFalse(CalendarBusy.isOnAt(nineToTen, 9.h - 1))
    }

    /** Back-to-back meetings must not both count at the moment they touch. */
    @Test
    fun `back to back appointments do not overlap at the boundary`() {
        val second = event(start = 10.h, end = 11.h)

        assertFalse(CalendarBusy.isOnAt(nineToTen, 10.h))
        assertTrue(CalendarBusy.isOnAt(second, 10.h))
    }

    @Test
    fun `busy at asks every appointment and ignores the ones that do not count`() {
        val events = listOf(
            nineToTen.copy(availability = CalendarAvailability.FREE),
            nineToTen.copy(status = CalendarEventStatus.CANCELLED),
            event(start = 14.h, end = 15.h),
        )

        assertFalse(CalendarBusy.busyAt(events, 9.h + 30.m))
        assertTrue(CalendarBusy.busyAt(events, 14.h + 30.m))
        assertFalse(CalendarBusy.busyAt(emptyList(), 14.h + 30.m))
    }

    private fun event(start: Long, end: Long) = CalendarEventRecord(
        ref = "evt:1|2|$start|Meeting",
        title = "Meeting",
        startEpochMs = start,
        endEpochMs = end,
    )

    private val Int.h: Long get() = this * 60L * 60L * 1000L
    private val Int.m: Long get() = this * 60L * 1000L
}
