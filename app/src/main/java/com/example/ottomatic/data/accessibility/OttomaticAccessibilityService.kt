package com.example.ottomatic.data.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.FingerprintGestureController
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import com.example.ottomatic.core.model.NodeId
import com.example.ottomatic.core.trigger.TriggerBus
import com.example.ottomatic.core.trigger.TriggerEvent
import com.example.ottomatic.core.trigger.TriggerSource
import com.example.ottomatic.data.capabilities.AndroidCapabilityChecker
import com.example.ottomatic.domain.registry.DeviceCapabilities

/**
 * Reports hardware volume-key presses and fingerprint-reader swipes to the engine.
 *
 * An accessibility service is the only way an Android app can observe key events
 * it did not receive itself — there is no other API, which is why every
 * automation app that supports button triggers ships one. The same is true of
 * fingerprint gestures, one API over: `FingerprintGestureController` is reachable
 * from nowhere else.
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
 * content, so it never *observes* anything but key events and swipes: no window
 * titles, no text on screen, no notion of which app is in front. What it can
 * additionally do since `action.screenshot` is **capture a single frame when a
 * macro asks it to** — `android:canTakeScreenshot`, which the config XML grants
 * and [ScreenCapture] is the only caller of. That is a deliberate widening and
 * worth naming as one: an on-demand screenshot is screen content. The distinction
 * the service still keeps is between watching and being asked — nothing here
 * reads the screen unprompted, on a timer, or in the background.
 *
 * **Fingerprint gestures are the second widening, and much the smaller one.** What
 * arrives is a swipe direction, four values wide, carrying nothing about whose
 * finger it was or whether it matched — `FingerprintGestureController` exposes no
 * such thing. The platform also withholds gestures entirely while the sensor is
 * authenticating, so ordinary unlock is untouched and unobservable from here; this
 * service cannot interfere with one even by accident.
 *
 * What *is* worth knowing is that most phones deliver no gestures at all. Only a
 * physical, usually rear-mounted reader reports swipes; in-display optical and
 * ultrasonic sensors generally report nothing, and `FEATURE_FINGERPRINT` does not
 * tell the two apart. That is why availability is republished into
 * [DeviceCapabilities] rather than the trigger simply going quiet: silence is what
 * a working trigger looks like while it waits, so the difference has to be said out
 * loud, in the Problems panel.
 *
 * [instance] is how a macro reaches it. A bound service cannot be constructed or
 * looked up, and the volume path only ever emits *outwards* onto [TriggerBus], so
 * a call going the other way needs the live binding the system holds. It is null
 * whenever the user has not enabled the service, which is exactly the state
 * `action.screenshot` reports as a missing grant — and the state in which nobody
 * can yet say whether this phone's reader does gestures.
 *
 * Nothing here runs until the user enables the service in Settings; until then
 * it is simply never bound, and `trigger.volume_button` and
 * `trigger.fingerprint_gesture` stay silent. That is why both nodes declare a
 * [com.example.ottomatic.core.permissions.PrerequisiteType.ACCESSIBILITY_SERVICE]
 * prerequisite, which the editor surfaces with a link to the right page.
 *
 * Payload contract, keys:
 * - `triggerType` — `"key"`
 * - `key` ∈ `"volume_up"`, `"volume_down"`
 * - `action` ∈ `"down"`, `"up"`
 * - `repeat` — the key's auto-repeat count
 * - `eventTime` — monotonic (boot-relative) event time, in ms
 * - `timestamp` — wall clock, epoch ms
 *
 * Payload contract, fingerprint gestures:
 * - `triggerType` — `"fingerprint"`
 * - `gesture` ∈ `"swipe_up"`, `"swipe_down"`, `"swipe_left"`, `"swipe_right"`
 * - `timestamp` — wall clock, epoch ms
 *
 * The fingerprint contract is duplicated on the engine side, because `data` may not
 * import `engine`; `FingerprintGestureRegistryTest` is what stops the two drifting, and
 * `VolumeButtonActivationTest` does the same for the key one.
 */
class OttomaticAccessibilityService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    /**
     * Registering [gestureCallback] here rather than lazily, because it is also how
     * the *availability* answer arrives: `onGestureDetectionAvailabilityChanged`
     * fires on registration, so this is the moment a phone that cannot do gestures
     * becomes knowable. Republished either way, since binding at all is news —
     * [AndroidCapabilityChecker] answers UNKNOWN until there is a controller to ask.
     */
    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        fingerprintGestureController?.registerFingerprintGestureCallback(gestureCallback, null)
        republishCapabilities()
    }

    // Both, and not only onDestroy: a service can be unbound and rebound without
    // being destroyed, and a stale reference here would have a macro calling
    // through to a service the system no longer considers connected.
    override fun onUnbind(intent: android.content.Intent?): Boolean {
        teardown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        teardown()
        super.onDestroy()
    }

    /**
     * Unregistering *before* clearing [instance], not after: the capability checker
     * reaches the controller through [instance], and the other order leaves a window
     * where it answers about a service that has already given the callback back.
     */
    private fun teardown() {
        fingerprintGestureController?.unregisterFingerprintGestureCallback(gestureCallback)
        instance = null
        republishCapabilities()
    }

    /**
     * Re-reads what this phone can do, now that the answer may have changed.
     *
     * `data` may reach `domain`, so the service publishes directly rather than going
     * back out through `ServiceLocator`. That matters: this fires while the app may
     * not be in the foreground at all, which is exactly when `MainActivity.onResume`
     * cannot help.
     */
    private fun republishCapabilities() {
        DeviceCapabilities.hydrateFrom(AndroidCapabilityChecker(applicationContext))
    }

    private val gestureCallback = object : FingerprintGestureController.FingerprintGestureCallback() {

        override fun onGestureDetected(gesture: Int) {
            val direction = when (gesture) {
                FingerprintGestureController.FINGERPRINT_GESTURE_SWIPE_UP -> GESTURE_SWIPE_UP
                FingerprintGestureController.FINGERPRINT_GESTURE_SWIPE_DOWN -> GESTURE_SWIPE_DOWN
                FingerprintGestureController.FINGERPRINT_GESTURE_SWIPE_LEFT -> GESTURE_SWIPE_LEFT
                FingerprintGestureController.FINGERPRINT_GESTURE_SWIPE_RIGHT -> GESTURE_SWIPE_RIGHT
                else -> return
            }
            // One event per deliberate swipe, so the bus is the right channel here for
            // the reason the key path gives — unlike the sensor stream, which would
            // swamp it.
            TriggerBus.emit(
                TriggerEvent(
                    source = TriggerSource.HARDWARE,
                    triggerNodeId = NodeId.BROADCAST,
                    payload = mapOf(
                        KEY_TRIGGER_TYPE to FINGERPRINT_TRIGGER_TYPE,
                        KEY_GESTURE to direction,
                        KEY_TIMESTAMP to System.currentTimeMillis().toString(),
                    ),
                ),
            )
        }

        // Not merely an optimisation over reading availability once at startup: the
        // platform can withdraw gesture detection mid-session, and a node that
        // silently stops working is the failure the Problems panel exists to catch.
        override fun onGestureDetectionAvailabilityChanged(available: Boolean) {
            republishCapabilities()
        }
    }

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
        /**
         * The connected service, or null when the user has not enabled it.
         *
         * `@Volatile` because it is written on the main thread by the system and read
         * from whichever coroutine a macro happens to be running on.
         */
        @Volatile
        var instance: OttomaticAccessibilityService? = null
            private set

        const val TRIGGER_TYPE = "key"
        const val FINGERPRINT_TRIGGER_TYPE = "fingerprint"

        const val KEY_VOLUME_UP = "volume_up"
        const val KEY_VOLUME_DOWN = "volume_down"

        const val ACTION_DOWN = "down"
        const val ACTION_UP = "up"

        const val GESTURE_SWIPE_UP = "swipe_up"
        const val GESTURE_SWIPE_DOWN = "swipe_down"
        const val GESTURE_SWIPE_LEFT = "swipe_left"
        const val GESTURE_SWIPE_RIGHT = "swipe_right"

        const val KEY_TRIGGER_TYPE = "triggerType"
        const val KEY_KEY = "key"
        const val KEY_ACTION = "action"
        const val KEY_REPEAT = "repeat"
        const val KEY_EVENT_TIME = "eventTime"
        const val KEY_GESTURE = "gesture"
        const val KEY_TIMESTAMP = "timestamp"
    }
}
