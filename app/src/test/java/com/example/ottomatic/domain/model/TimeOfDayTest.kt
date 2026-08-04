package com.example.ottomatic.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TimeOfDayTest {

    @Test
    fun `an HH mm string parses`() {
        assertEquals(TimeOfDay.of(7, 30), TimeOfDay.parse("07:30"))
        assertEquals(TimeOfDay.of(22, 0), TimeOfDay.parse("22:00"))
        assertEquals(TimeOfDay.of(0, 0), TimeOfDay.parse("00:00"))
    }

    @Test
    fun `surrounding whitespace is ignored`() {
        assertEquals(TimeOfDay.of(7, 30), TimeOfDay.parse(" 7:30 "))
    }

    /** Somebody halfway through typing has a bare hour in the box. */
    @Test
    fun `a bare hour means the hour`() {
        assertEquals(TimeOfDay.of(7, 0), TimeOfDay.parse("7"))
    }

    /**
     * The clamp. Before this, `minutesOfDay("25:99")` produced 1 599 minutes past
     * midnight — not a time of day, and a window built on it could never be open.
     */
    @Test
    fun `an out-of-range time is clamped into a real day`() {
        assertEquals(TimeOfDay.of(23, 59), TimeOfDay.parse("25:99"))
        assertEquals(TimeOfDay.of(0, 0), TimeOfDay.parse("-3:-9"))
    }

    @Test
    fun `text that is not a time is null`() {
        assertNull(TimeOfDay.parse(""))
        assertNull(TimeOfDay.parse("   "))
        assertNull(TimeOfDay.parse("soon"))
        // A colon with nothing readable after it is half-typed, not "on the hour".
        assertNull(TimeOfDay.parse("7:"))
    }

    /** The persisted form is zero-padded, so `7:05` and `07:05` are never two values. */
    @Test
    fun `toString is the persisted zero-padded form`() {
        assertEquals("07:05", TimeOfDay.of(7, 5).toString())
        assertEquals("23:59", TimeOfDay.of(23, 59).toString())
        assertEquals("00:00", TimeOfDay.of(0, 0).toString())
    }

    @Test
    fun `parsing what toString wrote gives the same time`() {
        val time = TimeOfDay.of(18, 7)
        assertEquals(time, TimeOfDay.parse(time.toString()))
    }
}
