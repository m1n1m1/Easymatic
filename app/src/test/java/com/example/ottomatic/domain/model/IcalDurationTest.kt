package com.example.ottomatic.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The length of a repeating appointment, which is the only place that length is written
 * down — see [IcalDuration] for why reading `DTEND` instead silently resizes it.
 */
class IcalDurationTest {

    @Test
    fun `the ordinary shapes a calendar writes`() {
        assertEquals(60L, IcalDuration.parseMinutes("PT1H"))
        assertEquals(90L, IcalDuration.parseMinutes("PT1H30M"))
        assertEquals(90L, IcalDuration.parseMinutes("PT90M"))
        assertEquals(24L * 60, IcalDuration.parseMinutes("P1D"))
        assertEquals(7L * 24 * 60, IcalDuration.parseMinutes("P1W"))
        assertEquals(26L * 60 + 30, IcalDuration.parseMinutes("P1DT2H30M"))
    }

    /** Rounded up, so an appointment shorter than a minute does not become no minutes. */
    @Test
    fun `seconds round up to a whole minute`() {
        assertEquals(2L, IcalDuration.parseMinutes("PT90S"))
        assertEquals(1L, IcalDuration.parseMinutes("PT1S"))
        assertEquals(1L, IcalDuration.parseMinutes("PT60S"))
    }

    /** Legal in the format and meaningless for an appointment, so accepted and ignored. */
    @Test
    fun `a leading sign is tolerated`() {
        assertEquals(60L, IcalDuration.parseMinutes("+PT1H"))
        assertEquals(60L, IcalDuration.parseMinutes("-PT1H"))
    }

    @Test
    fun `lower case parses, because providers are not consistent about it`() {
        assertEquals(60L, IcalDuration.parseMinutes("pt1h"))
    }

    @Test
    fun `anything unreadable answers null rather than a guess`() {
        assertNull(IcalDuration.parseMinutes(null))
        assertNull(IcalDuration.parseMinutes(""))
        assertNull(IcalDuration.parseMinutes("   "))
        assertNull(IcalDuration.parseMinutes("P"))
        // Not a duration at all — a DTEND somebody put in the wrong column.
        assertNull(IcalDuration.parseMinutes("20260803T090000Z"))
        // A unit with no number in front of it.
        assertNull(IcalDuration.parseMinutes("PTxH"))
        // A `T` announcing a time part that is not there.
        assertNull(IcalDuration.parseMinutes("P1DT"))
        // Zero length is not a length; the caller uses its own default.
        assertNull(IcalDuration.parseMinutes("PT0M"))
    }
}
