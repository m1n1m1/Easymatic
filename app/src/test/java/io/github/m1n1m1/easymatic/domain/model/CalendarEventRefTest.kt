package io.github.m1n1m1.easymatic.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The occurrence spec, pinned in both directions.
 *
 * The instance start is the field these tests exist for: without it a reference names
 * the *series*, and "delete this appointment" deletes every stand-up there will ever be.
 */
class CalendarEventRefTest {

    @Test
    fun `a formatted reference parses back to what it named`() {
        val spec = CalendarEventRef.format(7L, 42L, 1_754_200_800_000L, "Standup")
        val parsed = CalendarEventRef.parse(spec)!!

        assertEquals(7L, parsed.calendarId)
        assertEquals(42L, parsed.eventId)
        assertEquals(1_754_200_800_000L, parsed.instanceStartMs)
        assertEquals("Standup", parsed.title)
    }

    /**
     * Two occurrences of one repeating appointment differ only in the instance start,
     * and that is exactly the difference "Only this appointment" acts on.
     */
    @Test
    fun `two occurrences of one series differ only in the start`() {
        val tuesday = CalendarEventRef.parse(CalendarEventRef.format(7L, 42L, 1_754_200_800_000L, "Standup"))!!
        val wednesday = CalendarEventRef.parse(CalendarEventRef.format(7L, 42L, 1_754_287_200_000L, "Standup"))!!

        assertEquals(tuesday.eventId, wednesday.eventId)
        assertEquals(1_754_200_800_000L, tuesday.instanceStartMs)
        assertEquals(1_754_287_200_000L, wednesday.instanceStartMs)
    }

    /** Appointments are called "Standup | Team A"; the title is last and unsplit. */
    @Test
    fun `a title containing the separator survives the round trip`() {
        val spec = CalendarEventRef.format(7L, 42L, 1_754_200_800_000L, "Standup | Team A")

        assertEquals("Standup | Team A", CalendarEventRef.parse(spec)!!.title)
    }

    @Test
    fun `an untitled appointment is not a parse failure`() {
        val spec = CalendarEventRef.format(7L, 42L, 1_754_200_800_000L, "")

        assertEquals("", CalendarEventRef.parse(spec)!!.title)
        assertEquals(42L, CalendarEventRef.parse(spec)!!.eventId)
    }

    @Test
    fun `anything malformed parses to nothing at all`() {
        assertNull(CalendarEventRef.parse(""))
        // No prefix: plain text, or a title somebody wired in by mistake.
        assertNull(CalendarEventRef.parse("Standup"))
        assertNull(CalendarEventRef.parse("7|42|1754200800000|Standup"))
        // Too few fields — an event id with no occurrence, which must not be read as
        // "the whole series" by accident.
        assertNull(CalendarEventRef.parse("evt:7|42|Standup"))
        assertNull(CalendarEventRef.parse("evt:7|42||Standup"))
        assertNull(CalendarEventRef.parse("evt:seven|42|1754200800000|Standup"))
    }
}
