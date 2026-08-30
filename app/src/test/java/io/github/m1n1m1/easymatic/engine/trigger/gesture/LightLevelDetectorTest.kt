package io.github.m1n1m1.easymatic.engine.trigger.gesture

import io.github.m1n1m1.easymatic.engine.trigger.SensorSample
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Covers the hysteresis band and the dwell, which between them are what stop a
 * hand passing over the sensor, or walking under a row of ceiling lights, from
 * firing a macro.
 */
class LightLevelDetectorTest {

    @Test
    fun `the room going dark fires once`() {
        val run = light {
            level(BRIGHT, 5000)
            level(DARK, 5000)
        }

        assertEquals(listOf("below"), run.eventsFrom(detector(Threshold.BELOW)::update))
    }

    @Test
    fun `a brief shadow does not fire`() {
        // A hand passing over the sensor: dark, but not for the dwell.
        val run = light {
            level(BRIGHT, 5000)
            level(DARK, 1000)
            level(BRIGHT, 5000)
        }

        assertEquals(emptyList<String>(), run.eventsFrom(detector(Threshold.BELOW)::update))
    }

    @Test
    fun `a change-only stream never completes the dwell`() {
        // Documents why SensorBridge republishes on-change sensors. Light and
        // proximity report when their value *changes* and then go silent, and
        // the dwell here is measured from sample timestamps — so fed only the
        // raw change events, the room can go dark and stay dark and this fires
        // nothing at all. Which is precisely what happened on device.
        val changeEventsOnly = listOf(
            SensorSample(x = BRIGHT, y = 0f, z = 0f, elapsedMs = 0),
            SensorSample(x = DARK, y = 0f, z = 0f, elapsedMs = 5000),
        )

        assertEquals(
            emptyList<String>(),
            changeEventsOnly.eventsFrom(detector(Threshold.BELOW)::update),
        )
    }

    @Test
    fun `the level at activation is not an event`() {
        // Arming a macro in a dark room must not immediately fire "it got dark".
        val run = light { level(DARK, 8000) }

        assertEquals(emptyList<String>(), run.eventsFrom(detector(Threshold.BELOW)::update))
    }

    @Test
    fun `a level hovering at the threshold fires once and does not flap`() {
        // Six crossings, one event. The level really did fall below the threshold,
        // so staying silent would be wrong; what the hysteresis has to prevent is
        // the five further events, and it does — a reading inside the band never
        // re-arms the trigger, so nothing after the first crossing is decisive.
        val run = light {
            level(BRIGHT, 5000)
            repeat(6) {
                level(JUST_UNDER, 1000)
                level(JUST_OVER, 1000)
            }
        }

        assertEquals(listOf("below"), run.eventsFrom(detector(Threshold.BELOW)::update))
    }

    /**
     * The threshold the user typed is the level that fires. Centring the hysteresis
     * band on it instead moves the real edge to `threshold - hysteresis`, and a room
     * that settles between the two never fires at all however long it stays there —
     * which reads on device as the sensor being wrong rather than the trigger.
     */
    @Test
    fun `a room settling just below the threshold fires`() {
        val run = light {
            level(BRIGHT, 5000)
            level(JUST_UNDER, 8000)
        }

        assertEquals(listOf("below"), run.eventsFrom(detector(Threshold.BELOW)::update))
    }

    @Test
    fun `it re-arms only once the level has come back past the band`() {
        val run = light {
            level(BRIGHT, 5000)
            level(DARK, 5000)
            // Back over the threshold but still inside the band: not a reset.
            level(JUST_OVER, 5000)
            level(DARK, 5000)
            // Clear of the band, so the next fall counts again.
            level(BRIGHT, 5000)
            level(DARK, 5000)
        }

        assertEquals(listOf("below", "below"), run.eventsFrom(detector(Threshold.BELOW)::update))
    }

    @Test
    fun `it reports the measured level so a graph can use it`() {
        val fires = light {
            level(BRIGHT, 5000)
            level(DARK, 5000)
        }.firesFrom(detector(Threshold.BELOW)::update)

        assertEquals(DARK, fires.single().value, 0.01f)
    }

    @Test
    fun `the opposite direction fires on getting brighter`() {
        val run = light {
            level(DARK, 5000)
            level(BRIGHT, 5000)
        }

        assertEquals(listOf("above"), run.eventsFrom(detector(Threshold.ABOVE)::update))
    }

    @Test
    fun `the same change is detected identically at every sample rate`() {
        fun eventsAt(sampleMs: Long) = light(sampleMs) {
            level(BRIGHT, 5000)
            level(DARK, 5000)
        }.eventsFrom(detector(Threshold.BELOW)::update)

        assertEquals(listOf("below"), eventsAt(SAMPLE_MS))
        assertEquals(eventsAt(SAMPLE_MS), eventsAt(SLOW_SAMPLE_MS))
    }

    // Both pinned rather than left to the defaults: these cases are about the shape
    // of the band, and they should keep testing that shape when the defaults move
    // for device reasons.
    private fun detector(direction: Threshold) = LightLevelDetector(
        direction = direction,
        thresholdLux = THRESHOLD_LUX,
        hysteresisLux = HYSTERESIS_LUX,
    )

    private fun light(sampleMs: Long = SAMPLE_MS, block: LightRun.() -> Unit): List<SensorSample> =
        LightRun(sampleMs).apply(block).samples

    private class LightRun(private val sampleMs: Long) {
        val samples = mutableListOf<SensorSample>()
        private var nowMs = 0L

        fun level(lux: Float, durationMs: Long) {
            val until = nowMs + durationMs
            while (nowMs < until) {
                samples += SensorSample(x = lux, y = 0f, z = 0f, elapsedMs = nowMs)
                nowMs += sampleMs
            }
        }
    }

    private companion object {
        const val THRESHOLD_LUX = 10f
        const val HYSTERESIS_LUX = 5f

        /** Clear of the threshold and of the 5 lux re-arm band above it. */
        const val DARK = 2f
        const val BRIGHT = 400f

        /** Below the threshold, so decisive. */
        const val JUST_UNDER = 8f

        /** Back above the threshold but still inside the re-arm band. */
        const val JUST_OVER = 12f

        const val SAMPLE_MS = 200L
        const val SLOW_SAMPLE_MS = 1000L
    }
}
