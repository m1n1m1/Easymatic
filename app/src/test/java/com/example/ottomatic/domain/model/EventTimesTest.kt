package com.example.ottomatic.domain.model

import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The all-day shift, pinned in both directions and in both hemispheres.
 *
 * This is the test that earns its keep more than any other in the calendar work. The bug
 * it guards against produces no error, no log line and no wrong-looking number — an
 * all-day appointment on the 3rd is simply reported as beginning on the 2nd at 23:00, and
 * a macro for "the morning of a public holiday" runs the evening before. Every case here
 * is written with an explicit zone rather than the system default, because a test that
 * passes only in the developer's timezone is exactly the shape of the bug.
 */
class EventTimesTest {

    private val vienna = ZoneId.of("Europe/Vienna")
    private val chatham = ZoneId.of("Pacific/Chatham")
    private val losAngeles = ZoneId.of("America/Los_Angeles")

    /** Midnight UTC on 2026-08-03, which is how the provider stores "all day on the 3rd". */
    private val thirdUtcMidnight = Instant.parse("2026-08-03T00:00:00Z").toEpochMilli()

    @Test
    fun `an all-day event east of UTC starts at local midnight, not the evening before`() {
        val local = EventTimes.fromProvider(thirdUtcMidnight, allDay = true, zone = vienna)

        assertEquals("2026-08-03T00:00+02:00[Europe/Vienna]", Instant.ofEpochMilli(local).atZone(vienna).toString())
    }

    @Test
    fun `an all-day event west of UTC starts at local midnight, not the same afternoon`() {
        val local = EventTimes.fromProvider(thirdUtcMidnight, allDay = true, zone = losAngeles)

        assertEquals(
            "2026-08-03T00:00-07:00[America/Los_Angeles]",
            Instant.ofEpochMilli(local).atZone(losAngeles).toString(),
        )
    }

    /** A three-quarter-hour offset is where an implementation that assumes whole hours falls over. */
    @Test
    fun `an all-day event survives a fractional-hour offset`() {
        val local = EventTimes.fromProvider(thirdUtcMidnight, allDay = true, zone = chatham)

        assertEquals("2026-08-03T00:00", Instant.ofEpochMilli(local).atZone(chatham).toLocalDateTime().toString())
    }

    @Test
    fun `a timed event passes through untouched in both directions`() {
        val at = Instant.parse("2026-08-03T09:30:00Z").toEpochMilli()

        assertEquals(at, EventTimes.fromProvider(at, allDay = false, zone = vienna))
        assertEquals(at, EventTimes.toProvider(at, allDay = false, zone = vienna))
    }

    @Test
    fun `the two conversions are inverse for an all-day event`() {
        for (zone in listOf(vienna, losAngeles, chatham)) {
            val local = EventTimes.fromProvider(thirdUtcMidnight, allDay = true, zone = zone)

            assertEquals(
                "round trip in $zone",
                thirdUtcMidnight,
                EventTimes.toProvider(local, allDay = true, zone = zone),
            )
        }
    }

    /**
     * Writing an all-day event from a local instant *anywhere in that day* must name the
     * same date — which is the whole reason a form can offer an ordinary date picker.
     */
    @Test
    fun `any instant during a local day writes back as that day's UTC midnight`() {
        val lateThatEvening = Instant.parse("2026-08-03T21:15:00+02:00").toEpochMilli()

        assertEquals(thirdUtcMidnight, EventTimes.toProvider(lateThatEvening, allDay = true, zone = vienna))
    }

    /**
     * The end is exclusive, so a single all-day appointment covers exactly one day and
     * `start <= now < end` answers correctly on that day and on no other.
     */
    @Test
    fun `a one-day all-day event ends at UTC midnight the next day`() {
        val start = EventTimes.fromProvider(thirdUtcMidnight, allDay = true, zone = vienna)
        val end = EventTimes.allDayEnd(start, days = 1, zone = vienna)

        assertEquals(Instant.parse("2026-08-04T00:00:00Z").toEpochMilli(), end)
    }

    @Test
    fun `a multi-day all-day event spans the days it was given`() {
        val start = EventTimes.fromProvider(thirdUtcMidnight, allDay = true, zone = vienna)

        assertEquals(Instant.parse("2026-08-06T00:00:00Z").toEpochMilli(), EventTimes.allDayEnd(start, 3, vienna))
    }

    /** Zero days is not a length anything can mean; it is clamped rather than producing an empty event. */
    @Test
    fun `a zero-day span is clamped to one day`() {
        val start = EventTimes.fromProvider(thirdUtcMidnight, allDay = true, zone = vienna)

        assertEquals(EventTimes.allDayEnd(start, 1, vienna), EventTimes.allDayEnd(start, 0, vienna))
    }

    /**
     * Pinned as the literal string rather than derived from [ZoneOffset.UTC], whose id is
     * `"Z"` — which the provider does not accept. This is the value that goes into
     * `Events.EVENT_TIMEZONE`, and an all-day event stored against anything else is read
     * back at a shifted time by every other calendar app on the phone.
     */
    @Test
    fun `the provider zone constant is the one the format requires`() {
        assertEquals("UTC", EventTimes.ALL_DAY_ZONE)
        assertEquals(ZoneOffset.UTC.normalized(), ZoneId.of(EventTimes.ALL_DAY_ZONE).normalized())
    }
}
