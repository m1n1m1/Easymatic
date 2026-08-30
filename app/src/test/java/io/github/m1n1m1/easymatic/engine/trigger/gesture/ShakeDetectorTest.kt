package io.github.m1n1m1.easymatic.engine.trigger.gesture

import io.github.m1n1m1.easymatic.engine.trigger.gesture.SampleRun.Position
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The point of these is the *negative* cases. Firing on a shake is easy; not
 * firing when the phone is knocked, set down hard or tilted is what makes the
 * trigger usable, and that is what direction reversal buys.
 */
class ShakeDetectorTest {

    @Test
    fun `a shake fires once`() {
        val run = SampleRun(RATE_HZ)
            .rest(Position.FACE_UP, SEED_MS)
            .shake(Position.FACE_UP, FIRM, HALF_PERIOD_MS, cycles = 4)
            .rest(Position.FACE_UP, SEED_MS)
            .build()

        assertEquals(listOf("shake"), run.eventsFrom(ShakeDetector()::update))
    }

    @Test
    fun `a single hard knock does not fire`() {
        // One impact, however violent, has no reversal in it.
        val run = SampleRun(RATE_HZ)
            .rest(Position.FACE_UP, SEED_MS)
            .tap(Position.FACE_UP, amplitude = 40f, gapMs = SEED_MS)
            .build()

        assertEquals(emptyList<String>(), run.eventsFrom(ShakeDetector()::update))
    }

    @Test
    fun `a brief wobble is not a shake`() {
        // Three alternating jerks — one direction change more than a knock that
        // bounced, one fewer than the four the gesture requires. This is the bar
        // that ordinary handling kept clearing before it was raised.
        val run = SampleRun(RATE_HZ)
            .rest(Position.FACE_UP, SEED_MS)
            .hold(FIRM, 0f, Position.FACE_UP.z, HALF_PERIOD_MS)
            .hold(-FIRM, 0f, Position.FACE_UP.z, HALF_PERIOD_MS)
            .hold(FIRM, 0f, Position.FACE_UP.z, HALF_PERIOD_MS)
            .rest(Position.FACE_UP, SEED_MS)
            .build()

        assertEquals(emptyList<String>(), run.eventsFrom(ShakeDetector()::update))
    }

    @Test
    fun `slowly tilting the device does not fire`() {
        val run = SampleRun(RATE_HZ)
            .rest(Position.FACE_UP, SEED_MS)
            .rest(Position.TILTED, SEED_MS)
            .rest(Position.PORTRAIT, SEED_MS)
            .build()

        assertEquals(emptyList<String>(), run.eventsFrom(ShakeDetector()::update))
    }

    @Test
    fun `a second shake inside the cooldown is swallowed`() {
        val run = SampleRun(RATE_HZ)
            .rest(Position.FACE_UP, SEED_MS)
            .shake(Position.FACE_UP, FIRM, HALF_PERIOD_MS, cycles = 4)
            .rest(Position.FACE_UP, 200)
            .shake(Position.FACE_UP, FIRM, HALF_PERIOD_MS, cycles = 4)
            .rest(Position.FACE_UP, SEED_MS)
            .build()

        assertEquals(listOf("shake"), run.eventsFrom(ShakeDetector()::update))
    }

    @Test
    fun `sensitivity decides how hard the shake has to be`() {
        fun eventsAt(sensitivity: Sensitivity) = SampleRun(RATE_HZ)
            .rest(Position.FACE_UP, SEED_MS)
            .shake(Position.FACE_UP, GENTLE, HALF_PERIOD_MS, cycles = 4)
            .rest(Position.FACE_UP, SEED_MS)
            .build()
            .eventsFrom(ShakeDetector(sensitivity)::update)

        assertEquals(listOf("shake"), eventsAt(Sensitivity.HIGH))
        assertEquals(emptyList<String>(), eventsAt(Sensitivity.LOW))
    }

    @Test
    fun `the reported value grows with how hard the device was shaken`() {
        fun peakFor(amplitude: Float): Float = SampleRun(RATE_HZ)
            .rest(Position.FACE_UP, SEED_MS)
            .shake(Position.FACE_UP, amplitude, HALF_PERIOD_MS, cycles = 4)
            .rest(Position.FACE_UP, SEED_MS)
            .build()
            .firesFrom(ShakeDetector(Sensitivity.HIGH)::update)
            .single()
            .value

        // This is what a graph compares against, so it has to track the real
        // gesture rather than being a constant. It reads slightly *above* the
        // driven amplitude: at each reversal the gravity estimate has drifted
        // towards the previous direction, so the net acceleration relative to it
        // is larger than what was driven.
        val gentle = peakFor(GENTLE)
        val firm = peakFor(FIRM)
        assertTrue("a firm shake ($firm) should read harder than a gentle one ($gentle)", firm > gentle)
        assertTrue("peak $firm should stay in the same order as the driven $FIRM", firm < FIRM * 2f)
    }

    @Test
    fun `the same shake is detected identically at every sample rate`() {
        fun eventsAt(rateHz: Int) = SampleRun(rateHz)
            .rest(Position.FACE_UP, SEED_MS)
            .shake(Position.FACE_UP, FIRM, HALF_PERIOD_MS, cycles = 4)
            .rest(Position.FACE_UP, SEED_MS)
            .build()
            .eventsFrom(ShakeDetector()::update)

        assertEquals(listOf("shake"), eventsAt(RATE_HZ))
        assertEquals(eventsAt(RATE_HZ), eventsAt(FAST_RATE_HZ))
    }

    private companion object {
        const val RATE_HZ = 50
        const val FAST_RATE_HZ = 200

        /** Enough for the gravity filter to settle before the gesture starts. */
        const val SEED_MS = 500L

        const val HALF_PERIOD_MS = 60L

        /** Comfortably over the MEDIUM threshold of 14 m/s². */
        const val FIRM = 20f

        /** Over HIGH (10) but under LOW (18), once filter absorption is allowed for. */
        const val GENTLE = 12f
    }
}
