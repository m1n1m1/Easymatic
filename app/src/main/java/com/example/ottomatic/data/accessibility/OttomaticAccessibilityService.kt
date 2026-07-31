package com.example.ottomatic.data.accessibility

import android.accessibilityservice.AccessibilityService
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import com.example.ottomatic.core.model.NodeId
import com.example.ottomatic.core.trigger.TriggerBus
import com.example.ottomatic.core.trigger.TriggerEvent
import com.example.ottomatic.core.trigger.TriggerSource

/**
 * Reports hardware volume-key presses to the engine.
 *
 * An accessibility service is the only way an Android app can observe key events
 * it did not receive itself — there is no other API, which is why every
 * automation app that supports button triggers ships one.
 *
 * **This service never consumes a key event.** [onKeyEvent] returns `false` on
 * every path, so the volume rocker keeps working exactly as it did. That is not
 * a courtesy, it is forced: a press *sequence* cannot know at the first press
 * whether the sequence will complete, so consuming would mean swallowing
 * speculatively and re-injecting the presses that turned out not to match — and
 * an `AccessibilityService` has no key-injection API (`dispatchGesture` handles
 * touch only). The only implementable alternative would be to consume the volume
 * keys unconditionally, which would break them system-wide including on the lock
 * screen. A user who wants the volume change undone can put an `action.volume`
 * in the graph, where it is visible.
 *
 * The service declares no accessibility event types and cannot retrieve window
 * content, so key events are the entire surface it has. It sees no screen
 * content at any point.
 *
 * Nothing here runs until the user enables the service in Settings; until then
 * it is simply never bound, and `trigger.volume_button` stays silent. That is
 * why the node declares a
 * [com.example.ottomatic.core.permissions.PrerequisiteType.ACCESSIBILITY_SERVICE]
 * prerequisite, which the editor surfaces with a link to the right page.
 *
 * Payload contract:
 * - `triggerType` — `"key"`
 * - `key` ∈ `"volume_up"`, `"volume_down"`
 * - `action` ∈ `"down"`, `"up"`
 * - `repeat` — the key's auto-repeat count
 * - `eventTime` — monotonic (boot-relative) event time, in ms
 * - `timestamp` — wall clock, epoch ms
 */
class OttomaticAccessibilityService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    @Suppress("ReturnCount") // A guard chain, and every exit is the same `false`.
    override fun onKeyEvent(event: KeyEvent?): Boolean {
        val keyEvent = event ?: return false
        val key = when (keyEvent.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> KEY_VOLUME_UP
            KeyEvent.KEYCODE_VOLUME_DOWN -> KEY_VOLUME_DOWN
            // Power is deliberately not handled: delivery is inconsistent across
            // OEMs and interfering with it risks leaving a device unusable.
            else -> return false
        }
        val action = when (keyEvent.action) {
            KeyEvent.ACTION_DOWN -> ACTION_DOWN
            KeyEvent.ACTION_UP -> ACTION_UP
            else -> return false
        }
        // At most a handful of events a second, so the bus is the right channel
        // here — unlike the sensor stream, which would swamp it.
        TriggerBus.emit(
            TriggerEvent(
                source = TriggerSource.HARDWARE,
                triggerNodeId = NodeId.BROADCAST,
                payload = mapOf(
                    KEY_TRIGGER_TYPE to TRIGGER_TYPE,
                    KEY_KEY to key,
                    KEY_ACTION to action,
                    KEY_REPEAT to keyEvent.repeatCount.toString(),
                    KEY_EVENT_TIME to keyEvent.eventTime.toString(),
                    KEY_TIMESTAMP to System.currentTimeMillis().toString(),
                ),
            ),
        )
        // Never consume. See the class doc.
        return false
    }

    companion object {
        const val TRIGGER_TYPE = "key"

        const val KEY_VOLUME_UP = "volume_up"
        const val KEY_VOLUME_DOWN = "volume_down"

        const val ACTION_DOWN = "down"
        const val ACTION_UP = "up"

        const val KEY_TRIGGER_TYPE = "triggerType"
        const val KEY_KEY = "key"
        const val KEY_ACTION = "action"
        const val KEY_REPEAT = "repeat"
        const val KEY_EVENT_TIME = "eventTime"
        const val KEY_TIMESTAMP = "timestamp"
    }
}
