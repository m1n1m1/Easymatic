package com.example.ottomatic.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The calendar spec, pinned in both directions.
 *
 * The stakes here are the ones the class KDoc names: a half-parsed reference that keeps
 * its id and loses its account would address *some* calendar, and writing an appointment
 * into a stranger's shared calendar is worse than reporting that there was nothing to
 * write into.
 */
class CalendarRefTest {

    @Test
    fun `a formatted reference parses back to what it named`() {
        val spec = CalendarRef.format("me@example.com", "com.google", 7L, "Work")
        val parsed = CalendarRef.parse(spec)!!

        assertEquals("me@example.com", parsed.accountName)
        assertEquals("com.google", parsed.accountType)
        assertEquals(7L, parsed.calendarId)
        assertEquals("Work", parsed.calendarName)
    }

    /**
     * People call a calendar "Work | Team", and the name is last and unsplit precisely
     * so that it survives. The three fields before it cannot contain a separator — an
     * address, a package name and digits.
     */
    @Test
    fun `a name containing the separator survives the round trip`() {
        val spec = CalendarRef.format("me@example.com", "com.google", 7L, "Work | Team")

        assertEquals("Work | Team", CalendarRef.parse(spec)!!.calendarName)
    }

    /**
     * A local calendar has no account name at all, which the provider represents with
     * the account type `LOCAL`. That is a real calendar and must parse.
     */
    @Test
    fun `a local calendar with no account name still parses`() {
        val spec = CalendarRef.format("", "LOCAL", 3L, "My calendar")
        val parsed = CalendarRef.parse(spec)!!

        assertEquals(3L, parsed.calendarId)
        assertEquals("My calendar", parsed.displayName)
    }

    /** The account name is the fallback label when the calendar itself is unnamed. */
    @Test
    fun `the display name falls back to the account`() {
        val spec = CalendarRef.format("me@example.com", "com.google", 7L, "")

        assertEquals("me@example.com", CalendarRef.parse(spec)!!.displayName)
    }

    @Test
    fun `anything malformed parses to nothing at all`() {
        assertNull(CalendarRef.parse(""))
        // No prefix: plain text somebody typed into a wired field.
        assertNull(CalendarRef.parse("me@example.com|com.google|7|Work"))
        // Too few fields — an older or truncated spec.
        assertNull(CalendarRef.parse("cal:me@example.com|com.google|7"))
        // An id that is not a number at all, which no provider ever produced.
        assertNull(CalendarRef.parse("cal:me@example.com|com.google|seven|Work"))
        // Neither an account nor a name: nothing here could ever be resolved.
        assertNull(CalendarRef.parse("cal:||7|"))
    }
}
