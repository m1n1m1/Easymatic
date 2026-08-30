package io.github.m1n1m1.easymatic.engine.trigger.gesture

import io.github.m1n1m1.easymatic.engine.trigger.gesture.SampleRun.Position
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The two extra conditions are what these cover: a pick-up must also *rotate*
 * the device, and a put-down must (by default) end with it lying flat. Without
 * them the trigger degrades into "something moved" and "the user stood still",
 * both of which fire constantly.
 */
class DeviceMotionDetectorTest {

    @Test
    fun `lifting the device off a surface reports a pick-up`() {
        val run = SampleRun(RATE_HZ)
            .rest(Position.FACE_UP, SETTLE_MS)
            .shake(Position.FACE_UP, HANDLING, HALF_PERIOD_MS, cycles = 2)
            .shake(Position.TILTED, HANDLING, HALF_PERIOD_MS, cycles = 12)
            .build()

        assertEquals(listOf("picked_up"), run.eventsFrom(DeviceMotionDetector()::update))
    }

    @Test
    fun `movement without rotation is not a pick-up`() {
        // A bumped table, or a lorry going past: the phone moves but keeps
        // pointing the same way.
        val run = SampleRun(RATE_HZ)
            .rest(Position.FACE_UP, SETTLE_MS)
            .shake(Position.FACE_UP, HANDLING, HALF_PERIOD_MS, cycles = 14)
            .build()

        assertEquals(emptyList<String>(), run.eventsFrom(DeviceMotionDetector()::update))
    }

    @Test
    fun `setting the device down flat reports a put-down`() {
        val run = SampleRun(RATE_HZ)
            .rest(Position.FACE_UP, SEED_MS)
            .shake(Position.TILTED, HANDLING, HALF_PERIOD_MS, cycles = 8)
            .rest(Position.FACE_UP, SETTLE_MS)
            .build()

        assertEquals(listOf("put_down"), run.eventsFrom(DeviceMotionDetector()::update))
    }

    @Test
    fun `merely holding the device still is not a put-down`() {
        // Ends upright in the hand rather than flat on a surface.
        val run = SampleRun(RATE_HZ)
            .rest(Position.FACE_UP, SEED_MS)
            .shake(Position.PORTRAIT, HANDLING, HALF_PERIOD_MS, cycles = 8)
            .rest(Position.PORTRAIT, SETTLE_MS)
            .build()

        assertEquals(emptyList<String>(), run.eventsFrom(DeviceMotionDetector()::update))
    }

    @Test
    fun `turning off the flat requirement accepts stopping in any position`() {
        val run = SampleRun(RATE_HZ)
            .rest(Position.FACE_UP, SEED_MS)
            .shake(Position.PORTRAIT, HANDLING, HALF_PERIOD_MS, cycles = 8)
            .rest(Position.PORTRAIT, SETTLE_MS)
            .build()

        assertEquals(
            listOf("put_down"),
            run.eventsFrom(DeviceMotionDetector(surfaceOnly = false)::update),
        )
    }

    @Test
    fun `sensitivity decides how gentle a pick-up can be`() {
        // Being lifted carefully rather than snatched: enough movement for the
        // default, not enough for the conservative setting.
        fun eventsAt(sensitivity: Sensitivity) = SampleRun(RATE_HZ)
            .rest(Position.FACE_UP, SETTLE_MS)
            .shake(Position.FACE_UP, GENTLE_HANDLING, HALF_PERIOD_MS, cycles = 8)
            .shake(Position.TILTED, GENTLE_HANDLING, HALF_PERIOD_MS, cycles = 14)
            .build()
            .eventsFrom(DeviceMotionDetector(sensitivity = sensitivity)::update)

        assertEquals(listOf("picked_up"), eventsAt(Sensitivity.MEDIUM))
        assertEquals(emptyList<String>(), eventsAt(Sensitivity.LOW))
    }

    @Test
    fun `the state at activation is not reported`() {
        // Arming while the phone lies still on a desk must not report a put-down.
        val run = SampleRun(RATE_HZ).rest(Position.FACE_UP, SETTLE_MS).build()

        assertEquals(emptyList<String>(), run.eventsFrom(DeviceMotionDetector()::update))
    }

    @Test
    fun `the same movement is detected identically at every sample rate`() {
        fun eventsAt(rateHz: Int) = SampleRun(rateHz)
            .rest(Position.FACE_UP, SETTLE_MS)
            .shake(Position.FACE_UP, HANDLING, HALF_PERIOD_MS, cycles = 2)
            .shake(Position.TILTED, HANDLING, HALF_PERIOD_MS, cycles = 12)
            .build()
            .eventsFrom(DeviceMotionDetector()::update)

        assertEquals(listOf("picked_up"), eventsAt(RATE_HZ))
        assertEquals(eventsAt(RATE_HZ), eventsAt(FAST_RATE_HZ))
    }

    private companion object {
        const val RATE_HZ = 50
        const val FAST_RATE_HZ = 200

        const val SEED_MS = 500L

        /**
         * Long enough for the motion energy left over from vigorous handling to
         * decay under the stationary threshold (~2 s) and then hold there for
         * the 800 ms dwell.
         */
        const val SETTLE_MS = 4000L

        const val HALF_PERIOD_MS = 60L

        /** Enough motion energy to clear the moving threshold at any sensitivity. */
        const val HANDLING = 12f

        /** Settles between the MEDIUM (1.5) and LOW (3.0) moving thresholds. */
        const val GENTLE_HANDLING = 2.5f
    }
}
