package io.github.m1n1m1.easymatic.engine.trigger.gesture

import io.github.m1n1m1.easymatic.engine.trigger.SensorSample
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * Splits raw accelerometer samples into the slow *gravity* component (which way
 * is down) and the fast *linear* component (how hard the device is being moved).
 * Every accelerometer detector in this package needs one or the other.
 *
 * The smoothing factor is derived from the **elapsed time between samples**
 * rather than fixed:
 *
 * ```
 * alpha = exp(-dt / timeConstant)
 * ```
 *
 * A hard-coded `alpha = 0.8` — the form this filter is usually written in —
 * silently means a 0.08 s time constant at 50 Hz and 0.02 s at 200 Hz. Since
 * [io.github.m1n1m1.easymatic.data.sensor.SensorBridge] shares one registration
 * across every subscriber and runs it at the fastest rate anyone asked for, a
 * fixed alpha would make a shake trigger behave differently depending on
 * whether a tap trigger happened to be armed at the same time. This form does
 * not care.
 *
 * A long gap between samples drives `alpha` to zero, which re-seeds gravity from
 * the current sample — the right behaviour after the sensor was suspended.
 */
class GravityFilter(private val timeConstantMs: Float = DEFAULT_TIME_CONSTANT_MS) {

    var gravityX: Float = 0f
        private set
    var gravityY: Float = 0f
        private set
    var gravityZ: Float = 0f
        private set

    var linearX: Float = 0f
        private set
    var linearY: Float = 0f
        private set
    var linearZ: Float = 0f
        private set

    private var lastElapsedMs: Long? = null

    /** Magnitude of the linear (gravity-removed) acceleration, in m/s². */
    val linearMagnitude: Float
        get() = sqrt(linearX * linearX + linearY * linearY + linearZ * linearZ)

    fun update(sample: SensorSample) {
        val previous = lastElapsedMs
        lastElapsedMs = sample.elapsedMs
        if (previous == null) {
            // Seed from the first sample rather than from zero. Starting at zero
            // makes the filter spend its first half-second climbing towards
            // gravity while reporting that climb as ~1 g of linear acceleration,
            // which reads as a shake to anything watching.
            gravityX = sample.x
            gravityY = sample.y
            gravityZ = sample.z
        } else {
            val dtMs = (sample.elapsedMs - previous).coerceAtLeast(0L).toFloat()
            val alpha = exp(-dtMs / timeConstantMs)
            gravityX = alpha * gravityX + (1f - alpha) * sample.x
            gravityY = alpha * gravityY + (1f - alpha) * sample.y
            gravityZ = alpha * gravityZ + (1f - alpha) * sample.z
        }
        linearX = sample.x - gravityX
        linearY = sample.y - gravityY
        linearZ = sample.z - gravityZ
    }

    private companion object {
        /**
         * 200 ms. Long enough that a deliberate gesture (tens of milliseconds)
         * stays entirely in the linear component, short enough that a genuine
         * re-orientation settles into gravity well inside the 700 ms dwell
         * [OrientationDetector] requires.
         */
        const val DEFAULT_TIME_CONSTANT_MS = 200f
    }
}
