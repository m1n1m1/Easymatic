package io.github.m1n1m1.easymatic.engine.trigger.gesture

import io.github.m1n1m1.easymatic.engine.trigger.gesture.SampleRun.Position
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tap detection lives or dies on rejecting things that are not taps, so most of
 * this is negative cases: a sustained movement, a ringing impact, and two taps
 * too far apart to be one gesture.
 */
class TapDetectorTest {

    @Test
    fun `two taps in quick succession fire`() {
        val run = SampleRun(RATE_HZ)
            .rest(Position.FACE_UP, SEED_MS)
            .tap(Position.FACE_UP, SHARP, gapMs = GAP_MS)
            .tap(Position.FACE_UP, SHARP, gapMs = SEED_MS)
            .build()

        assertEquals(listOf("double_tap"), run.eventsFrom(TapDetector()::update))
    }

    @Test
    fun `two taps too far apart are not one gesture`() {
        val run = SampleRun(RATE_HZ)
            .rest(Position.FACE_UP, SEED_MS)
            .tap(Position.FACE_UP, SHARP, gapMs = LONG_GAP_MS)
            .tap(Position.FACE_UP, SHARP, gapMs = SEED_MS)
            .build()

        assertEquals(emptyList<String>(), run.eventsFrom(TapDetector()::update))
    }

    @Test
    fun `a sustained movement is never a tap`() {
        // Same peak force, but held far longer than an impact lasts. This is the
        // case that separates a tap from a shake or a hard pick-up.
        val run = SampleRun(RATE_HZ)
            .rest(Position.FACE_UP, SEED_MS)
            .hold(SHARP, 0f, Position.FACE_UP.z, SUSTAINED_MS)
            .rest(Position.FACE_UP, SEED_MS)
            .hold(SHARP, 0f, Position.FACE_UP.z, SUSTAINED_MS)
            .rest(Position.FACE_UP, SEED_MS)
            .build()

        assertEquals(emptyList<String>(), run.eventsFrom(TapDetector()::update))
    }

    @Test
    fun `the ringing after one impact does not count as a second tap`() {
        // One physical tap on a rigid object is a decaying train of impacts, not
        // a single clean one. Both the refractory window and the fact that the
        // echoes fall under the threshold have to hold for this to read as one
        // tap; without either, every tap would report as a double.
        val run = SampleRun(RATE_HZ)
            .rest(Position.FACE_UP, SEED_MS)
            .tap(Position.FACE_UP, SHARP, gapMs = RING_GAP_MS)
            .tap(Position.FACE_UP, SHARP * FIRST_ECHO, gapMs = RING_GAP_MS)
            .tap(Position.FACE_UP, SHARP * SECOND_ECHO, gapMs = SEED_MS)
            .build()

        assertEquals(emptyList<String>(), run.eventsFrom(TapDetector()::update))
    }

    @Test
    fun `triple tap needs a third tap`() {
        fun eventsFor(taps: Int) = SampleRun(RATE_HZ)
            .rest(Position.FACE_UP, SEED_MS)
            .apply { repeat(taps) { tap(Position.FACE_UP, SHARP, gapMs = GAP_MS) } }
            .rest(Position.FACE_UP, SEED_MS)
            .build()
            .eventsFrom(TapDetector(TapCount.TRIPLE_TAP)::update)

        assertEquals(emptyList<String>(), eventsFor(2))
        assertEquals(listOf("triple_tap"), eventsFor(3))
    }

    @Test
    fun `sensitivity decides how hard the tap has to be`() {
        fun eventsAt(sensitivity: Sensitivity) = SampleRun(RATE_HZ)
            .rest(Position.FACE_UP, SEED_MS)
            .tap(Position.FACE_UP, LIGHT, gapMs = GAP_MS)
            .tap(Position.FACE_UP, LIGHT, gapMs = SEED_MS)
            .build()
            .eventsFrom(TapDetector(sensitivity = sensitivity)::update)

        assertEquals(listOf("double_tap"), eventsAt(Sensitivity.HIGH))
        assertEquals(emptyList<String>(), eventsAt(Sensitivity.LOW))
    }

    @Test
    fun `a hard tap registers just as a light one does`() {
        // Regression. The spike used to end at a fixed level, but the gravity
        // filter absorbs part of any impulse and releases it over ~200 ms — so
        // the harder the tap, the longer the reading stayed elevated, and the
        // firmest taps timed out and were thrown away as sustained movement.
        // Which is exactly what a user does when the trigger seems unresponsive.
        listOf(LIGHT, SHARP, VERY_HARD).forEach { amplitude ->
            val run = SampleRun(RATE_HZ)
                .rest(Position.FACE_UP, SEED_MS)
                .tap(Position.FACE_UP, amplitude, gapMs = GAP_MS)
                .tap(Position.FACE_UP, amplitude, gapMs = SEED_MS)
                .build()

            assertEquals(
                "at amplitude $amplitude",
                listOf("double_tap"),
                run.eventsFrom(TapDetector(sensitivity = Sensitivity.HIGH)::update),
            )
        }
    }

    @Test
    fun `the same taps are detected identically at every sample rate`() {
        fun eventsAt(rateHz: Int) = SampleRun(rateHz)
            .rest(Position.FACE_UP, SEED_MS)
            .tap(Position.FACE_UP, SHARP, gapMs = GAP_MS)
            .tap(Position.FACE_UP, SHARP, gapMs = SEED_MS)
            .build()
            .eventsFrom(TapDetector()::update)

        assertEquals(listOf("double_tap"), eventsAt(RATE_HZ))
        assertEquals(eventsAt(RATE_HZ), eventsAt(FASTER_RATE_HZ))
    }

    private companion object {
        const val RATE_HZ = 200
        const val FASTER_RATE_HZ = 500

        const val SEED_MS = 500L

        /** Comfortably inside the window that reads as one double tap. */
        const val GAP_MS = 200L

        /** Past the 500 ms limit, so these are two separate gestures. */
        const val LONG_GAP_MS = 600L

        /** Inside the 80 ms refractory, so it must be swallowed as ringing. */
        const val RING_GAP_MS = 40L

        /** How much of the original impact each echo carries. */
        const val FIRST_ECHO = 0.4f
        const val SECOND_ECHO = 0.15f

        /** Longer than the 120 ms an impact can last. */
        const val SUSTAINED_MS = 300L

        /** Well over the MEDIUM enter threshold of 8 m/s². */
        const val SHARP = 30f

        /** Over HIGH (5) but under LOW (14). */
        const val LIGHT = 10f

        /** A sharp knuckle rap on a phone lying on a desk — around 10 g. */
        const val VERY_HARD = 100f
    }
}
