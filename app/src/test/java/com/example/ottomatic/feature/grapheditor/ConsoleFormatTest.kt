package com.example.ottomatic.feature.grapheditor

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId

/**
 * The console's timestamps.
 *
 * Pinned because the obvious wrong implementation — arithmetic on the millis —
 * passes every test written in UTC and shows the wrong hour on a real phone.
 */
class ConsoleFormatTest {

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

    private fun ms(hour: Int, minute: Int, second: Int, millis: Int): Long =
        ((hour * 3600L + minute * 60L + second) * 1000L) + millis
}
