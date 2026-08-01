package com.example.ottomatic.engine.trigger.gesture

import com.example.ottomatic.domain.model.config.Label
import com.example.ottomatic.engine.trigger.SensorSample
import com.example.ottomatic.engine.trigger.payloadValue
import kotlinx.serialization.Serializable
import kotlin.math.min

/** What `trigger.proximity` watches for. */
@Serializable
enum class ProximityEvent {
    @Label("Covered")
    NEAR,

    @Label("Uncovered")
    FAR,
}

/**
 * Watches the proximity sensor — the one above the screen that blanks the
 * display during a call.
 *
 * **There is deliberately no wave gesture.** Phone proximity sensors are binary:
 * the hardware decides near and far for itself and reports only zero or its
 * maximum range, and on current handsets — where the sensor sits under the
 * display — it wants the hand within a few millimetres, in practice touching the
 * glass. Android exposes no way to change that distance, so a hand waved above
 * the phone produces no reading to detect and no amount of threshold tuning
 * helps. A "wave" that only works when you touch the screen is not a wave, so
 * this reports the covering itself and leaves it at that.
 *
 * The state at activation is never reported: arming a macro with the phone
 * already in a pocket must not fire it.
 */
@Suppress("ReturnCount") // A guard chain; nesting it would only hide the order of the checks.
class ProximityDetector(
    private val event: ProximityEvent,
    private val nearThresholdCm: Float,
) {

    private var near = false
    private var initialised = false
    private var changedAtMs = 0L

    fun update(sample: SensorSample): GestureFire? {
        val isNear = sample.x < nearThresholdCm
        if (!initialised) {
            initialised = true
            near = isNear
            changedAtMs = sample.elapsedMs
            return null
        }
        if (isNear == near) return null

        val heldMs = sample.elapsedMs - changedAtMs
        near = isNear
        changedAtMs = sample.elapsedMs
        // A flicker shorter than this is sensor noise, not a hand.
        if (heldMs < DEBOUNCE_MS) return null

        val wantsNear = event == ProximityEvent.NEAR
        return if (isNear == wantsNear) GestureFire(event.payloadValue) else null
    }

    companion object {
        private const val DEBOUNCE_MS = 100L

        /**
         * Ceiling on the "covered" threshold. Sensors that report a wide range in
         * centimetres would otherwise call a hand held 20 cm away a cover.
         */
        const val MAX_NEAR_THRESHOLD_CM = 5f

        /**
         * The distance below which a sensor of range [maxRangeCm] counts as
         * covered.
         *
         * A reading only means anything relative to the sensor's own range —
         * most phone sensors are effectively binary, reporting 0 when covered
         * and their maximum when clear, and that maximum is 3 cm on some devices
         * and 100 cm on others.
         *
         * Shared with `value.proximity` so the trigger and the value draw the
         * line in the same place.
         */
        fun nearThresholdCm(maxRangeCm: Float): Float = min(maxRangeCm, MAX_NEAR_THRESHOLD_CM)

        /** Whether [distanceCm] from a sensor of range [maxRangeCm] means covered. */
        fun isCovered(distanceCm: Float, maxRangeCm: Float): Boolean =
            distanceCm < nearThresholdCm(maxRangeCm)
    }
}
