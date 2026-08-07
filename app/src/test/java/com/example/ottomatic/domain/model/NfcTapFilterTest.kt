package com.example.ottomatic.domain.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which taps count as one, and which count as two. */
class NfcTapFilterTest {

    @Test
    fun `the first tap always counts`() {
        assertTrue(NfcTapFilter().accept("04A23F1B", 1_000))
    }

    /**
     * The case this exists for: a tag left resting on the phone is re-discovered by
     * some devices as the field cycles, and an impatient second tap follows a first
     * one that already worked.
     */
    @Test
    fun `the same tag again straight away does not`() {
        val filter = NfcTapFilter(windowMs = 1_000)

        assertTrue(filter.accept("04A23F1B", 1_000))
        assertFalse(filter.accept("04A23F1B", 1_400))
    }

    @Test
    fun `the same tag after the window is a new tap`() {
        val filter = NfcTapFilter(windowMs = 1_000)

        assertTrue(filter.accept("04A23F1B", 1_000))
        assertTrue(filter.accept("04A23F1B", 2_000))
    }

    /**
     * Why this is keyed on the id rather than being a plain cooldown: tapping the
     * desk tag and then the car tag is two intentions, and a bare "one tap per
     * second" rule would swallow the second.
     */
    @Test
    fun `a different tag inside the window still counts`() {
        val filter = NfcTapFilter(windowMs = 1_000)

        assertTrue(filter.accept("04A23F1B", 1_000))
        assertTrue(filter.accept("0BADC0DE", 1_100))
    }

    /** Rejecting must not extend the window, or holding a tag on would never clear it. */
    @Test
    fun `a rejected tap does not restart the clock`() {
        val filter = NfcTapFilter(windowMs = 1_000)

        assertTrue(filter.accept("04A23F1B", 1_000))
        assertFalse(filter.accept("04A23F1B", 1_500))
        assertTrue(filter.accept("04A23F1B", 2_000))
    }

    /** Back to a tag you tapped a moment ago, via another one, is a real tap. */
    @Test
    fun `going back to the previous tag counts`() {
        val filter = NfcTapFilter(windowMs = 1_000)

        assertTrue(filter.accept("04A23F1B", 1_000))
        assertTrue(filter.accept("0BADC0DE", 1_100))
        assertTrue(filter.accept("04A23F1B", 1_200))
    }
}
