package io.github.m1n1m1.easymatic.engine.trigger.gesture

import io.github.m1n1m1.easymatic.engine.trigger.gesture.SampleRun.Position
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Covers the three things that keep `trigger.device_orientation` from firing on
 * every wobble: the dwell requirement, the Schmitt-trigger hysteresis, and the
 * suppressed first commit.
 *
 * A settled orientation takes roughly a second to be reported — the gravity
 * filter needs ~350 ms to cross the enter threshold after a flip, then the
 * 700 ms dwell runs on top of that — so every hold here is 2 s.
 */
class OrientationDetectorTest {

    @Test
    fun `reports the new orientation after a flip`() {
        val run = SampleRun(RATE_HZ)
            .rest(Position.FACE_UP, SETTLE_MS)
            .rest(Position.FACE_DOWN, SETTLE_MS)
            .build()

        assertEquals(listOf("face_down"), run.eventsFrom(OrientationDetector()::update))
    }

    @Test
    fun `the orientation at activation is not reported`() {
        // Arming a macro while the phone already lies face down must not fire it.
        val run = SampleRun(RATE_HZ).rest(Position.FACE_DOWN, SETTLE_MS).build()

        assertEquals(emptyList<String>(), run.eventsFrom(OrientationDetector()::update))
    }

    @Test
    fun `a flip that reverts inside the dwell is not reported`() {
        val run = SampleRun(RATE_HZ)
            .rest(Position.FACE_UP, SETTLE_MS)
            .rest(Position.FACE_DOWN, BRIEF_MS)
            .rest(Position.FACE_UP, SETTLE_MS)
            .build()

        assertEquals(emptyList<String>(), run.eventsFrom(OrientationDetector()::update))
    }

    @Test
    fun `staying in an orientation reports it only once`() {
        val run = SampleRun(RATE_HZ)
            .rest(Position.FACE_UP, SETTLE_MS)
            .rest(Position.FACE_DOWN, SETTLE_MS)
            .rest(Position.FACE_DOWN, SETTLE_MS)
            .build()

        assertEquals(listOf("face_down"), run.eventsFrom(OrientationDetector()::update))
    }

    @Test
    fun `passing through an orientation reports only where it settles`() {
        // Rotating from face up to face down via portrait: portrait is crossed
        // too briefly to clear the dwell, so it must not be reported.
        val run = SampleRun(RATE_HZ)
            .rest(Position.FACE_UP, SETTLE_MS)
            .rest(Position.PORTRAIT, BRIEF_MS)
            .rest(Position.FACE_DOWN, SETTLE_MS)
            .build()

        assertEquals(listOf("face_down"), run.eventsFrom(OrientationDetector()::update))
    }

    @Test
    fun `an ambiguous tilt holds the committed orientation`() {
        // No axis reaches the enter threshold, so hysteresis must keep face-up
        // rather than dropping the classification and re-committing it.
        val run = SampleRun(RATE_HZ)
            .rest(Position.FACE_UP, SETTLE_MS)
            .rest(Position.AMBIGUOUS, SETTLE_MS)
            .rest(Position.FACE_UP, SETTLE_MS)
            .build()

        assertEquals(emptyList<String>(), run.eventsFrom(OrientationDetector()::update))
    }

    @Test
    fun `each resting position reports its own name`() {
        val run = SampleRun(RATE_HZ)
            .rest(Position.FACE_UP, SETTLE_MS)
            .rest(Position.PORTRAIT, SETTLE_MS)
            .rest(Position.LANDSCAPE_LEFT, SETTLE_MS)
            .rest(Position.LANDSCAPE_RIGHT, SETTLE_MS)
            .rest(Position.PORTRAIT_UPSIDE_DOWN, SETTLE_MS)
            .rest(Position.FACE_DOWN, SETTLE_MS)
            .build()

        assertEquals(
            listOf("portrait", "landscape_left", "landscape_right", "portrait_upside_down", "face_down"),
            run.eventsFrom(OrientationDetector()::update),
        )
    }

    /**
     * The load-bearing one.
     *
     * [io.github.m1n1m1.easymatic.data.sensor.SensorBridge] shares a single
     * accelerometer registration across every armed gesture trigger and runs it
     * at the fastest rate anyone asked for. So arming a tap macro — which wants
     * 200 Hz — silently speeds up the samples this detector sees. If any timing
     * here were derived from a sample count rather than from
     * [io.github.m1n1m1.easymatic.engine.trigger.SensorSample.elapsedMs], that would
     * change what an orientation macro does.
     */
    @Test
    fun `the same gesture is detected identically at every sample rate`() {
        fun eventsAt(rateHz: Int) = SampleRun(rateHz)
            .rest(Position.FACE_UP, SETTLE_MS)
            .rest(Position.PORTRAIT, BRIEF_MS)
            .rest(Position.FACE_DOWN, SETTLE_MS)
            .build()
            .eventsFrom(OrientationDetector()::update)

        val slow = eventsAt(SLOW_RATE_HZ)
        assertEquals(listOf("face_down"), slow)
        assertEquals(slow, eventsAt(RATE_HZ))
        assertEquals(slow, eventsAt(FAST_RATE_HZ))
    }

    private companion object {
        const val RATE_HZ = 15
        const val SLOW_RATE_HZ = 5
        const val FAST_RATE_HZ = 200

        /** Comfortably past the ~350 ms filter lag plus the 700 ms dwell. */
        const val SETTLE_MS = 2000L

        /** Short enough that the dwell never completes. */
        const val BRIEF_MS = 400L
    }
}
