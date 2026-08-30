package io.github.m1n1m1.easymatic.engine.trigger.keys

import io.github.m1n1m1.easymatic.domain.model.config.Label
import kotlinx.serialization.Serializable

/** The hardware keys `trigger.volume_button` can watch. */
@Serializable
enum class VolumeKey {
    @Label("Volume up")
    VOLUME_UP,

    @Label("Volume down")
    VOLUME_DOWN,
}

/** How the key has to be pressed. */
@Serializable
enum class KeyGesture {
    @Label("Pressed")
    PRESS,

    @Label("Held down")
    LONG_PRESS,

    @Label("Pressed several times")
    SEQUENCE,
}

/**
 * One key event, as reported by the accessibility service.
 *
 * [eventTimeMs] comes from `KeyEvent.getEventTime`, which is monotonic
 * (boot-relative), so a clock adjustment cannot corrupt a press sequence.
 *
 * [repeatCount] matters more than it looks: holding a key down delivers a stream
 * of DOWN events with an increasing repeat count, and counting those would let
 * a single long hold satisfy a "press three times" sequence.
 */
data class KeyPress(
    val key: VolumeKey,
    val down: Boolean,
    val repeatCount: Int,
    val eventTimeMs: Long,
)

/** Recognises one key gesture from a stream of [KeyPress]es. */
fun interface KeyGestureDetector {
    /** True when [press] completes the gesture. */
    fun update(press: KeyPress): Boolean
}

/** Fires on each fresh press of [key]. */
class KeyPressDetector(private val key: VolumeKey) : KeyGestureDetector {
    override fun update(press: KeyPress): Boolean =
        press.down && press.repeatCount == 0 && press.key == key
}

/**
 * Fires when [key] is released after being held for at least [holdMs].
 *
 * Firing on *release* rather than at the moment the threshold passes is forced
 * rather than chosen: reporting mid-hold would mean deciding to swallow the key
 * before knowing how long it would be held, and the service never consumes keys.
 */
class KeyLongPressDetector(
    private val key: VolumeKey,
    private val holdMs: Long = DEFAULT_HOLD_MS,
) : KeyGestureDetector {

    private var downAtMs: Long? = null

    @Suppress("ReturnCount") // A guard chain; nesting it would only hide the order of the checks.
    override fun update(press: KeyPress): Boolean {
        if (press.key != key) {
            downAtMs = null
            return false
        }
        if (press.down) {
            // Ignore auto-repeat; the hold started at the first DOWN.
            if (press.repeatCount == 0) downAtMs = press.eventTimeMs
            return false
        }
        val downAt = downAtMs ?: return false
        downAtMs = null
        return press.eventTimeMs - downAt >= holdMs
    }

    companion object {
        const val DEFAULT_HOLD_MS = 800L
    }
}

/**
 * Fires when [key] is pressed [presses] times inside [windowMs].
 *
 * Pressing the *other* volume key clears the run: "volume down three times" is a
 * deliberate gesture, and someone adjusting the volume up and down in between
 * plainly did not mean it.
 */
class KeySequenceDetector(
    private val key: VolumeKey,
    private val presses: Int = DEFAULT_PRESSES,
    private val windowMs: Long = DEFAULT_WINDOW_MS,
) : KeyGestureDetector {

    private val downs = ArrayDeque<Long>()

    @Suppress("ReturnCount") // A guard chain; nesting it would only hide the order of the checks.
    override fun update(press: KeyPress): Boolean {
        if (!press.down || press.repeatCount > 0) return false
        if (press.key != key) {
            downs.clear()
            return false
        }
        while (downs.isNotEmpty() && press.eventTimeMs - downs.first() > windowMs) {
            downs.removeFirst()
        }
        downs.addLast(press.eventTimeMs)
        if (downs.size < presses) return false
        downs.clear()
        return true
    }

    companion object {
        const val DEFAULT_PRESSES = 3
        const val DEFAULT_WINDOW_MS = 2000L
    }
}
