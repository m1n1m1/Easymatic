package com.example.ottomatic.engine.trigger.gesture

import com.example.ottomatic.engine.trigger.SensorSample
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
    fun `a level hovering at the threshold does not flap`() {
        // Inside the hysteresis band the previous answer holds, so this reports
        // nothing at all rather than firing on every crossing.
        val run = light {
            level(BRIGHT, 5000)
            repeat(6) {
                level(JUST_UNDER, 1000)
                level(JUST_OVER, 1000)
            }
        }

        assertEquals(emptyList<String>(), run.eventsFrom(detector(Threshold.BELOW)::update))
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

    private fun detector(direction: Threshold) = LightLevelDetector(
        direction = direction,
        thresholdLux = THRESHOLD_LUX,
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

        /** Well outside the ±5 lux hysteresis band. */
        const val DARK = 2f
        const val BRIGHT = 400f

        /** Both inside the band, so neither is decisive. */
        const val JUST_UNDER = 8f
        const val JUST_OVER = 12f

        const val SAMPLE_MS = 200L
        const val SLOW_SAMPLE_MS = 1000L
    }
}
