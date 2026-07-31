package com.example.ottomatic.engine.trigger.gesture

import com.example.ottomatic.domain.model.config.Label
import com.example.ottomatic.engine.trigger.SensorSample
import com.example.ottomatic.engine.trigger.payloadValue
import kotlinx.serialization.Serializable

/** Which way a level has to cross a threshold to count. */
@Serializable
enum class Threshold {
    @Label("Rises above")
    ABOVE,

    @Label("Falls below")
    BELOW,
}

/**
 * Fires when the ambient light level crosses a threshold and stays there.
 *
 * Two guards, both necessary. A **hysteresis band** around the threshold stops a
 * level hovering right at it from firing repeatedly; between the two edges the
 * previous answer holds. A **dwell** then requires the new state to persist, which
 * is what separates "the room lights went out" from a hand passing over the
 * sensor or walking under a row of ceiling lights.
 *
 * Three seconds is close to the practical floor for the dwell anyway: many
 * devices heavily quantise and rate-limit their ambient light sensor, so a
 * shorter window would be measuring the sensor's reporting behaviour rather than
 * the room.
 */
@Suppress("ReturnCount") // A guard chain; nesting it would only hide the order of the checks.
class LightLevelDetector(
    private val direction: Threshold,
    private val thresholdLux: Float,
    private val hysteresisLux: Float = DEFAULT_HYSTERESIS_LUX,
    private val dwellMs: Long = DEFAULT_DWELL_MS,
) {

    private var committed: Boolean? = null
    private var candidate: Boolean? = null
    private var candidateSinceMs = 0L

    fun update(sample: SensorSample): GestureFire? {
        val lux = sample.x
        val high = thresholdLux + hysteresisLux
        val low = thresholdLux - hysteresisLux
        val instant = when {
            lux > high -> direction == Threshold.ABOVE
            lux < low -> direction == Threshold.BELOW
            // Inside the band the reading is not decisive either way.
            else -> return null
        }

        if (instant != candidate) {
            candidate = instant
            candidateSinceMs = sample.elapsedMs
            return null
        }
        if (instant == committed) return null
        if (sample.elapsedMs - candidateSinceMs < dwellMs) return null

        val previous = committed
        committed = instant
        // Only the transition *into* the wanted state is an event, and the first
        // classification only describes how bright it already was.
        if (previous == null || !instant) return null
        return GestureFire(event = direction.payloadValue, value = lux)
    }

    companion object {
        const val DEFAULT_HYSTERESIS_LUX = 5f
        const val DEFAULT_DWELL_MS = 3000L

        /** Roughly a dim room; a lit office is hundreds of lux. */
        const val DEFAULT_THRESHOLD_LUX = 10f
    }
}
