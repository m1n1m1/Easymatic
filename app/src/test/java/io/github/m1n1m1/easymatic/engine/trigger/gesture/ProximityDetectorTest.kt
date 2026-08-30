package io.github.m1n1m1.easymatic.engine.trigger.gesture

import io.github.m1n1m1.easymatic.engine.trigger.SensorSample
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * There is no wave gesture to test: phone proximity sensors are binary and want
 * the hand touching the glass, so a wave never produces a reading at all. See
 * [ProximityDetector]. What is left is the covering itself, plus the two rules
 * that stop it firing when it should not — noise, and the state at activation.
 */
class ProximityDetectorTest {

    @Test
    fun `covering and uncovering report their own transitions`() {
        val run = proximity(clearMs = 500, coveredMs = 800, thenClearMs = 500)

        assertEquals(listOf("near"), run.eventsFrom(detector(ProximityEvent.NEAR)::update))
        assertEquals(listOf("far"), run.eventsFrom(detector(ProximityEvent.FAR)::update))
    }

    @Test
    fun `a flicker too short to be a hand is ignored`() {
        val run = proximity(clearMs = 500, coveredMs = 40, thenClearMs = 500)

        assertEquals(emptyList<String>(), run.eventsFrom(detector(ProximityEvent.FAR)::update))
    }

    @Test
    fun `starting out covered is not an event`() {
        // Arming a macro with the phone already in a pocket must not report one.
        val run = buildProximity { covered(2000) }

        assertEquals(emptyList<String>(), run.eventsFrom(detector(ProximityEvent.NEAR)::update))
    }

    @Test
    fun `staying covered reports it only once`() {
        val run = proximity(clearMs = 500, coveredMs = 5000, thenClearMs = 0)

        assertEquals(listOf("near"), run.eventsFrom(detector(ProximityEvent.NEAR)::update))
    }

    @Test
    fun `change events alone are enough`() {
        // This is what lets proximity skip the republishing that light needs:
        // every interval it measures runs between two real transitions, so a
        // stream that reports only changes — which is all an on-change sensor
        // does — still carries all the timing. The one thing it does need is the
        // reading at activation, supplied here as the first sample.
        val changeEventsOnly = listOf(
            SensorSample(x = CLEAR, y = 0f, z = 0f, elapsedMs = 0),
            SensorSample(x = COVERED, y = 0f, z = 0f, elapsedMs = 4000),
        )

        assertEquals(listOf("near"), changeEventsOnly.eventsFrom(detector(ProximityEvent.NEAR)::update))
    }

    private fun detector(event: ProximityEvent) =
        ProximityDetector(event = event, nearThresholdCm = NEAR_THRESHOLD)

    private fun proximity(clearMs: Long, coveredMs: Long, thenClearMs: Long) = buildProximity {
        clear(clearMs)
        covered(coveredMs)
        clear(thenClearMs)
    }

    /**
     * Proximity is an on-change sensor, but sampling it steadily is both simpler
     * and a stricter test: the detector has to ignore the repeats itself.
     */
    private fun buildProximity(block: ProximityRun.() -> Unit): List<SensorSample> =
        ProximityRun().apply(block).samples

    private class ProximityRun {
        val samples = mutableListOf<SensorSample>()
        private var nowMs = 0L

        fun clear(durationMs: Long) = emit(CLEAR, durationMs)

        fun covered(durationMs: Long) = emit(COVERED, durationMs)

        private fun emit(value: Float, durationMs: Long) {
            val until = nowMs + durationMs
            while (nowMs < until) {
                samples += SensorSample(x = value, y = 0f, z = 0f, elapsedMs = nowMs)
                nowMs += SAMPLE_MS
            }
        }
    }

    private companion object {
        /** A short-range binary sensor, as most phones have. */
        const val RANGE_CM = 3f
        const val NEAR_THRESHOLD = RANGE_CM
        const val COVERED = 0f
        const val CLEAR = RANGE_CM
        const val SAMPLE_MS = 20L
    }
}
