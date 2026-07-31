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
 * Two guards, both necessary. A **hysteresis band** stops a level hovering right
 * at the threshold from firing repeatedly; inside it the previous answer holds. A
 * **dwell** then requires the new state to persist, which is what separates "the
 * room lights went out" from a hand passing over the sensor or walking under a row
 * of ceiling lights.
 *
 * The band is deliberately **one-sided**: the threshold itself is the firing edge,
 * and the hysteresis only sets how far the level has to come back before the
 * trigger re-arms. Centring the band on the threshold instead — the obvious
 * reading of "ignore changes smaller than this" — quietly moves the firing edge by
 * half the band, so "falls below 30 lux" with the default 10 lux of hysteresis
 * would need the room to reach 20 lux and a room genuinely sitting at 25 would
 * never fire at all. The number the user typed has to be the number that fires.
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
        // The threshold fires; the band only sets where the trigger re-arms, on the
        // far side of it. Between the two the reading is not decisive either way.
        val instant = when (direction) {
            Threshold.ABOVE -> when {
                lux > thresholdLux -> true
                lux < thresholdLux - hysteresisLux -> false
                else -> return null
            }
            Threshold.BELOW -> when {
                lux < thresholdLux -> true
                lux > thresholdLux + hysteresisLux -> false
                else -> return null
            }
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
        /**
         * Wide enough to clear [DEFAULT_THRESHOLD_LUX]'s unreliable zone on the way
         * back: re-arming at 40 lux means the room has to reach a level the sensor
         * can actually resolve before the trigger will fire again.
         */
        const val DEFAULT_HYSTERESIS_LUX = 10f

        const val DEFAULT_DWELL_MS = 3000L

        /**
         * A dim room, and about as low as the default can usefully go.
         *
         * Phone ambient light sensors sit under the display and are heavily
         * quantised and rate-limited at the bottom of their range; below roughly
         * 30 lux the reported level stops tracking the room and starts reporting
         * the sensor's own step size, so a threshold under that fires on noise or
         * not at all. Anyone who genuinely wants "pitch dark" can still type a
         * smaller number — this is only where the field starts.
         *
         * For scale on the other side: a lit office is a few hundred lux.
         */
        const val DEFAULT_THRESHOLD_LUX = 30f
    }
}
