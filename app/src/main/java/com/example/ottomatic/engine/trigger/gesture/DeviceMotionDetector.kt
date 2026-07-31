package com.example.ottomatic.engine.trigger.gesture

import com.example.ottomatic.engine.trigger.SensorSample
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * Reports the device being picked up off a surface, or set back down on one.
 *
 * Movement is measured as a rolling RMS of the linear acceleration, decayed by
 * elapsed time rather than by sample count so the answer does not depend on
 * whatever rate the shared sensor registration happens to be running at. A state
 * change has to hold for a dwell before it is committed — long for settling
 * (a pause mid-gesture is not a put-down), short for departure.
 *
 * Two extra conditions carry most of the weight:
 *
 *  - **pick-up also requires the device to have tilted** by more than
 *    [TILT_DEGREES] over the second that follows. Nudging a table, or a passing
 *    lorry, moves a phone without rotating it; picking one up essentially always
 *    rotates it.
 *  - **put-down optionally requires the device to end up flat**
 *    ([surfaceOnly], on by default). Without it, "put down" degrades to "stopped
 *    moving", which is true every time the user stands still while holding the
 *    phone, and the trigger fires constantly.
 *
 * Nothing fires until the detector has classified the device once, so arming a
 * macro does not report the state the device was already in.
 */
@Suppress("ReturnCount") // Guard chains throughout; nesting them would hide the order of the checks.
class DeviceMotionDetector(
    private val surfaceOnly: Boolean = true,
    sensitivity: Sensitivity = Sensitivity.MEDIUM,
    private val filter: GravityFilter = GravityFilter(),
) {

    /** How much movement counts as "moving" at all. */
    private val movingRms: Float = when (sensitivity) {
        Sensitivity.LOW -> LOW_MOVING_RMS
        Sensitivity.MEDIUM -> MEDIUM_MOVING_RMS
        Sensitivity.HIGH -> HIGH_MOVING_RMS
    }

    /** How far the device has to turn for a pick-up to count. */
    private val tiltDegrees: Float = when (sensitivity) {
        Sensitivity.LOW -> LOW_TILT_DEGREES
        Sensitivity.MEDIUM -> MEDIUM_TILT_DEGREES
        Sensitivity.HIGH -> HIGH_TILT_DEGREES
    }

    /**
     * A second, much slower gravity estimate used **only** for the tilt check.
     *
     * [filter] has to react quickly enough to separate a gesture from gravity,
     * which leaves its output wobbling by a couple of degrees while the device
     * is being shaken. That is harmless for measuring movement and fatal for
     * measuring rotation: at the tilt thresholds the sensitivity setting now
     * reaches, the wobble alone reads as a turn, and shaking a phone without
     * rotating it at all registered as picking it up. Averaging over a second
     * leaves a real rotation intact and the wobble far below any threshold.
     */
    private val tiltFilter = GravityFilter(timeConstantMs = TILT_TIME_CONSTANT_MS)

    private var rmsSquared = 0f
    private var lastElapsedMs: Long? = null

    /** Null until the device has been classified once. */
    private var committedStationary: Boolean? = null
    private var candidate: Boolean? = null
    private var candidateSinceMs = 0L

    private var departureGravity: FloatArray? = null
    private var tiltDeadlineMs = 0L
    private var cooldownUntilMs = Long.MIN_VALUE

    fun update(sample: SensorSample): GestureFire? {
        filter.update(sample)
        tiltFilter.update(sample)
        updateMotionEnergy(sample.elapsedMs)
        if (sample.elapsedMs < cooldownUntilMs) return null

        pendingPickUp(sample.elapsedMs)?.let { return it }
        return updateSettledState(sample.elapsedMs)
    }

    private fun updateMotionEnergy(elapsedMs: Long) {
        val previous = lastElapsedMs
        lastElapsedMs = elapsedMs
        val dtMs = if (previous == null) 0f else (elapsedMs - previous).coerceAtLeast(0L).toFloat()
        val beta = exp(-dtMs / RMS_TIME_CONSTANT_MS)
        val magnitude = filter.linearMagnitude
        rmsSquared = beta * rmsSquared + (1f - beta) * magnitude * magnitude
    }

    private val rms: Float get() = sqrt(rmsSquared)

    private fun updateSettledState(elapsedMs: Long): GestureFire? {
        val instant = when {
            rms < STATIONARY_RMS -> true
            rms > movingRms -> false
            // Between the two thresholds the device is neither clearly still nor
            // clearly moving, so hold whatever was last committed.
            else -> return null
        }
        if (instant == committedStationary) {
            candidate = null
            return null
        }
        if (candidate != instant) {
            candidate = instant
            candidateSinceMs = elapsedMs
            return null
        }
        val dwellMs = if (instant) STATIONARY_DWELL_MS else MOVING_DWELL_MS
        if (elapsedMs - candidateSinceMs < dwellMs) return null

        val previous = committedStationary
        committedStationary = instant
        candidate = null
        // The first classification describes where the device already was.
        if (previous == null) return null
        return if (instant) onSettled(elapsedMs) else onDeparted(elapsedMs)
    }

    /** Started moving: arm the tilt check that separates a pick-up from a bump. */
    private fun onDeparted(elapsedMs: Long): GestureFire? {
        departureGravity = floatArrayOf(tiltFilter.gravityX, tiltFilter.gravityY, tiltFilter.gravityZ)
        tiltDeadlineMs = elapsedMs + TILT_WINDOW_MS
        return null
    }

    private fun onSettled(elapsedMs: Long): GestureFire? {
        departureGravity = null
        if (surfaceOnly && abs(filter.gravityZ) < FLAT_THRESHOLD) return null
        cooldownUntilMs = elapsedMs + COOLDOWN_MS
        return GestureFire(event = EVENT_PUT_DOWN)
    }

    private fun pendingPickUp(elapsedMs: Long): GestureFire? {
        val from = departureGravity ?: return null
        if (elapsedMs < tiltDeadlineMs) return null
        departureGravity = null
        val degrees = tiltDegreesSince(from)
        if (degrees < tiltDegrees) return null
        cooldownUntilMs = elapsedMs + COOLDOWN_MS
        return GestureFire(event = EVENT_PICKED_UP, value = degrees)
    }

    /** Angle between the gravity vector at [from] and the current one, in degrees. */
    private fun tiltDegreesSince(from: FloatArray): Float {
        val x = tiltFilter.gravityX
        val y = tiltFilter.gravityY
        val z = tiltFilter.gravityZ
        val fromLength = sqrt(from[0] * from[0] + from[1] * from[1] + from[2] * from[2])
        val nowLength = sqrt(x * x + y * y + z * z)
        if (fromLength == 0f || nowLength == 0f) return 0f
        val dot = from[0] * x + from[1] * y + from[2] * z
        val cosine = (dot / (fromLength * nowLength)).coerceIn(-1f, 1f)
        return Math.toDegrees(acos(cosine).toDouble()).toFloat()
    }

    private companion object {
        const val EVENT_PICKED_UP = "picked_up"
        const val EVENT_PUT_DOWN = "put_down"

        /**
         * How much history is in the motion estimate.
         *
         * This decays the *squared* magnitude, so the RMS itself decays with
         * twice this time constant. That doubling is easy to underestimate: at
         * one second here, the energy left over from a vigorous shake takes
         * about seven seconds to fall under [STATIONARY_RMS], and a put-down
         * would be reported long after the user had walked away. 350 ms still
         * averages over several cycles of any real gesture while letting the
         * device be called still around two seconds after it stops.
         */
        const val RMS_TIME_CONSTANT_MS = 350f

        const val STATIONARY_RMS = 0.4f

        // The two gates that reject a movement, and therefore what "sensitivity"
        // means for this gesture. Each step roughly doubles how easily it fires.
        // MEDIUM is twice as sensitive as the original fixed values, which are
        // what LOW now is; both gates stay clear of STATIONARY_RMS so the band
        // between "still" and "moving" cannot invert.

        /** The original fixed behaviour. */
        const val LOW_MOVING_RMS = 3f
        const val MEDIUM_MOVING_RMS = 1.5f
        const val HIGH_MOVING_RMS = 0.9f

        const val LOW_TILT_DEGREES = 25f
        const val MEDIUM_TILT_DEGREES = 12.5f
        const val HIGH_TILT_DEGREES = 8f

        /**
         * Settling is slower to commit than departure: a pause mid-gesture is
         * not a put-down. The decay above already supplies most of the
         * hysteresis, so this only has to cover a brief hesitation.
         */
        const val STATIONARY_DWELL_MS = 800L

        /** Departure is quick to commit, so the tilt window starts promptly. */
        const val MOVING_DWELL_MS = 150L

        const val TILT_WINDOW_MS = 1000L

        /**
         * Long enough to average out the wobble a shake leaves in the gravity
         * estimate. A real rotation still comes through: over [TILT_WINDOW_MS]
         * this reports about two thirds of it, which every threshold allows for.
         */
        const val TILT_TIME_CONSTANT_MS = 1000f

        /** ~0.87 g on the screen axis, i.e. lying nearly flat. */
        const val FLAT_THRESHOLD = 8.5f

        const val COOLDOWN_MS = 3000L
    }
}
