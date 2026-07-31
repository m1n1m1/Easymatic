package com.example.ottomatic.engine.trigger.gesture

import com.example.ottomatic.engine.trigger.SensorSample
import com.example.ottomatic.engine.trigger.payloadValue
import kotlin.math.abs
import kotlinx.serialization.Serializable

/**
 * The resting positions `trigger.device_orientation` can report.
 *
 * The vocabulary lives with the detector that produces it rather than with the
 * trigger, so the trigger's optional filter is a direct enum comparison instead
 * of a string match against something declared somewhere else.
 */
@Serializable
enum class DeviceOrientation {
    FACE_UP,
    FACE_DOWN,
    PORTRAIT,
    PORTRAIT_UPSIDE_DOWN,
    LANDSCAPE_LEFT,
    LANDSCAPE_RIGHT,
}

/**
 * Reports when the device comes to rest in a new orientation.
 *
 * Three things keep this from firing on every wobble:
 *
 *  - it classifies **gravity**, not raw acceleration, so waving the phone around
 *    in a fixed orientation changes nothing;
 *  - classification is a Schmitt trigger. An orientation is entered when its
 *    axis carries more than [ENTER_THRESHOLD] and is only left below
 *    [EXIT_THRESHOLD]; in between, the current classification holds. Without the
 *    gap, a phone held at roughly 45° flaps continuously;
 *  - a candidate must hold continuously for `dwellMs` before it is committed, so
 *    passing *through* portrait on the way from face-up to face-down reports one
 *    change rather than two.
 *
 * The first commit after activation is swallowed: arming a macro while the phone
 * is already face down should not fire it. This is the same rule the battery
 * level poll uses — emit on the transition into a state, not on discovering you
 * are already in it.
 */
class OrientationDetector(
    private val dwellMs: Long = DEFAULT_DWELL_MS,
    private val filter: GravityFilter = GravityFilter(),
) {

    private var committed: DeviceOrientation? = null
    private var pending: DeviceOrientation? = null
    private var pendingSinceMs: Long = 0
    private var primed: Boolean = false

    @Suppress("ReturnCount") // A guard chain; nesting it would only hide the order of the checks.
    fun update(sample: SensorSample): GestureFire? {
        filter.update(sample)
        val candidate = classify() ?: heldOrientation()

        if (candidate != pending) {
            pending = candidate
            pendingSinceMs = sample.elapsedMs
            return null
        }
        if (candidate == null || candidate == committed) return null
        if (sample.elapsedMs - pendingSinceMs < dwellMs) return null

        committed = candidate
        // Swallow the very first commit: it describes where the device already
        // was when the macro was armed, not a change the user made.
        if (!primed) {
            primed = true
            return null
        }
        return GestureFire(event = candidate.payloadValue)
    }

    /**
     * The orientation whose axis currently dominates, or null when the device is
     * held too ambiguously to call.
     *
     * Picking the *largest* component rather than testing the axes in a fixed
     * order matters: at 45° between portrait and face-up both axes clear
     * [ENTER_THRESHOLD] at once, and a fixed order would silently always prefer
     * the same one.
     */
    private fun classify(): DeviceOrientation? {
        val x = filter.gravityX
        val y = filter.gravityY
        val z = filter.gravityZ
        val ax = abs(x)
        val ay = abs(y)
        val az = abs(z)
        return when {
            az >= ax && az >= ay && az > ENTER_THRESHOLD ->
                if (z > 0f) DeviceOrientation.FACE_UP else DeviceOrientation.FACE_DOWN
            ay >= ax && ay >= az && ay > ENTER_THRESHOLD ->
                if (y > 0f) DeviceOrientation.PORTRAIT else DeviceOrientation.PORTRAIT_UPSIDE_DOWN
            ax >= ay && ax >= az && ax > ENTER_THRESHOLD ->
                if (x > 0f) DeviceOrientation.LANDSCAPE_LEFT else DeviceOrientation.LANDSCAPE_RIGHT
            else -> null
        }
    }

    /** The committed orientation, while its own axis has not yet decayed past [EXIT_THRESHOLD]. */
    private fun heldOrientation(): DeviceOrientation? {
        val current = committed ?: return null
        val component = when (current) {
            DeviceOrientation.FACE_UP, DeviceOrientation.FACE_DOWN -> filter.gravityZ
            DeviceOrientation.PORTRAIT, DeviceOrientation.PORTRAIT_UPSIDE_DOWN -> filter.gravityY
            DeviceOrientation.LANDSCAPE_LEFT, DeviceOrientation.LANDSCAPE_RIGHT -> filter.gravityX
        }
        return current.takeIf { abs(component) > EXIT_THRESHOLD }
    }

    companion object {
        /** 700 ms — long enough to pass through an orientation without reporting it. */
        const val DEFAULT_DWELL_MS = 700L

        /** ~0.66 g on one axis, i.e. within about 49° of it. */
        private const val ENTER_THRESHOLD = 0.66f * GRAVITY

        /** ~0.51 g. The gap below [ENTER_THRESHOLD] is what stops a 45° hold from flapping. */
        private const val EXIT_THRESHOLD = 0.51f * GRAVITY
    }
}
