package io.github.m1n1m1.easymatic.engine.trigger.gesture

import io.github.m1n1m1.easymatic.domain.model.config.Label
import kotlinx.serialization.Serializable

/**
 * A swipe across the fingerprint reader, as `trigger.fingerprint_gesture` filters on.
 *
 * **These four are the whole set, and that is the platform's doing rather than a
 * first cut.** `FingerprintGestureController` reports swipe up, down, left and right
 * and nothing else: there is no tap, no double-tap, no long-press and no hold in that
 * API, so there is nothing here to extend later. A reader is a direction sensor with
 * four answers.
 *
 * The classification lives here rather than in the node file for the reason
 * [OrientationDetector] and [ProximityDetector] do: the wire values are shared with
 * the accessibility service, which is in `data` and cannot import this, so both sides
 * name the same strings and a test pins them together.
 */
@Serializable
enum class FingerprintGesture {
    @Label("Swipe up")
    SWIPE_UP,

    @Label("Swipe down")
    SWIPE_DOWN,

    @Label("Swipe left")
    SWIPE_LEFT,

    @Label("Swipe right")
    SWIPE_RIGHT,
    ;

    /** How this gesture is spelled in a [io.github.m1n1m1.easymatic.core.trigger.TriggerEvent] payload. */
    val payloadValue: String
        get() = when (this) {
            SWIPE_UP -> VALUE_SWIPE_UP
            SWIPE_DOWN -> VALUE_SWIPE_DOWN
            SWIPE_LEFT -> VALUE_SWIPE_LEFT
            SWIPE_RIGHT -> VALUE_SWIPE_RIGHT
        }

    companion object {
        const val VALUE_SWIPE_UP = "swipe_up"
        const val VALUE_SWIPE_DOWN = "swipe_down"
        const val VALUE_SWIPE_LEFT = "swipe_left"
        const val VALUE_SWIPE_RIGHT = "swipe_right"

        /** Parses a payload value, or null when it names no gesture we know. */
        fun fromPayload(value: String?): FingerprintGesture? = when (value) {
            VALUE_SWIPE_UP -> SWIPE_UP
            VALUE_SWIPE_DOWN -> SWIPE_DOWN
            VALUE_SWIPE_LEFT -> SWIPE_LEFT
            VALUE_SWIPE_RIGHT -> SWIPE_RIGHT
            else -> null
        }
    }
}
