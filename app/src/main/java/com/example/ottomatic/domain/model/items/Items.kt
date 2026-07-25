package com.example.ottomatic.domain.model.items

import com.example.ottomatic.core.service.AudioStream
import com.example.ottomatic.core.service.DndLevel
import com.example.ottomatic.core.service.HttpMethod
import com.example.ottomatic.core.service.RingerMode
import com.example.ottomatic.core.service.VolumeMode
import kotlinx.serialization.Serializable

/**
 * A received SMS, exposed as a typed data item on the `trigger.sms` node's
 * `sms` output port.
 */
@Serializable
data class SmsMessage(
    val sender: String,
    val body: String,
    val timestamp: Long,
)

/** A received notification, exposed on `trigger.notification`'s `notification` port. */
@Serializable
data class NotificationEvent(
    val packageName: String,
    val title: String,
    val text: String,
    val timestamp: Long,
)

/**
 * A `trigger.schedule` fire, exposed on its `fireTime` port.
 *
 * - [firedAt]: epoch ms at which this fire occurred.
 * - [elapsedMs]: milliseconds since the trigger was activated — what the old
 *   `trigger.stopwatch` reported.
 * - [count]: 1-based occurrence number since activation.
 * - [hour], [minute]: wall-clock time of the fire, in the device's timezone.
 * - [dayOfWeek]: [java.util.Calendar] constant, 1 = Sunday.
 * - [dayOfMonth]: day of the month, 1-31.
 *
 * The calendar parts are carried so a downstream condition can branch on them
 * without recomputing anything. [elapsedMs] and [count] are relative to when the
 * trigger's flow was collected, so both restart when the workflow is re-armed or
 * the process is killed.
 */
@Serializable
data class ScheduleFire(
    val firedAt: Long,
    val elapsedMs: Long,
    val count: Int,
    val hour: Int,
    val minute: Int,
    val dayOfWeek: Int,
    val dayOfMonth: Int,
)

/** Request built by the HTTP action from its config + data inputs. */
@Serializable
data class HttpRequestItem(
    val method: HttpMethod,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val body: String = "",
)

/** Response returned by the HTTP action on its `response` data port. */
@Serializable
data class HttpResponseItem(
    val statusCode: Int,
    val body: String,
    val headers: Map<String, String> = emptyMap(),
)

/** Wi-Fi state reported by the wifi action on its `state` data port. */
@Serializable
data class WifiState(
    val enabled: Boolean,
    val changed: Boolean,
)

/**
 * Volume state reported by the volume action on its `state` data port.
 *
 * - [stream]: which audio stream was adjusted.
 * - [mode]: what was done.
 * - [volume]: resulting volume index (0..[maxVolume]).
 * - [maxVolume]: maximum index for the stream.
 * - [changed]: whether the system accepted the change.
 */
@Serializable
data class VolumeState(
    val stream: AudioStream,
    val mode: VolumeMode,
    val volume: Int,
    val maxVolume: Int,
    val changed: Boolean,
)

/**
 * Do-Not-Disturb state reported by the DND action on its `state` data port.
 *
 * - [enabled]: whether DND is now active.
 * - [level]: DND policy in effect ([DndLevel.ALL] when disabled).
 * - [changed]: whether the system accepted the change (requires the
 *   `ACCESS_NOTIFICATION_POLICY` permission).
 */
@Serializable
data class DndState(
    val enabled: Boolean,
    val level: DndLevel,
    val changed: Boolean,
)

/**
 * Battery state reported by `trigger.charging` and `trigger.battery_level`
 * on their `state` data port.
 *
 * - [isCharging]: whether the device is currently charging.
 * - [level]: battery percentage 0-100.
 * - [plugged]: power source — `"ac"`, `"usb"`, `"wireless"` or `null`.
 * - [event]: discriminator — `"charging_started"`, `"charging_stopped"` or
 *   `"level_poll"`.
 * - [timestamp]: when the event was produced.
 */
@Serializable
data class BatteryState(
    val isCharging: Boolean,
    val level: Int,
    val plugged: String?,
    val event: String,
    val timestamp: Long,
)

/** A duration value, in milliseconds, flowing into `action.delay`'s `duration` port. */
@Serializable
data class DurationMillis(
    val millis: Long,
)

/**
 * A geofence transition reported by `trigger.geofence` on its `event` data port.
 *
 * - [triggerNodeId]: the geofence trigger node that fired (matches the geofence
 *   `requestId` set by `AndroidTriggerHost.armGeofence`).
 * - [transition]: discriminator — `"enter"`, `"exit"` or `"dwell"`.
 * - [latitude], [longitude]: the triggering location.
 * - [accuracyMeters]: estimated accuracy of the triggering fix, or `0f` if
 *   unavailable.
 * - [timestamp]: when the transition was produced (epoch ms).
 */
@Serializable
data class GeofenceEvent(
    val triggerNodeId: String,
    val transition: String,
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float,
    val timestamp: Long,
)

/**
 * A variable change reported by `trigger.variable_change` on its `variable` data port.
 *
 * - [name]: the variable name.
 * - [value]: the new string value.
 * - [timestamp]: when the change was produced (epoch ms).
 */
@Serializable
data class VariableChange(
    val name: String,
    val value: String,
    val timestamp: Long,
)

/**
 * A device/UI mode change reported by `trigger.mode_change` on its `mode` data port.
 *
 * - [mode]: the new mode — `"normal"` or `"night"`.
 * - [timestamp]: when the change was produced (epoch ms).
 */
@Serializable
data class ModeChange(
    val mode: String,
    val timestamp: Long,
)

/**
 * Generic system-state event reported by Tier 1 broadcast-receiver triggers
 * (wifi, bluetooth, airplane, headset, usb, dock, screen, ringer, power-save,
 * clock-change, locale, shutdown, call) on their `state` data port.
 *
 * - [event]: discriminator — `"enabled"`, `"disabled"`, `"connected"`,
 *   `"disconnected"`, `"on"`, `"off"`, `"plugged"`, `"unplugged"`, etc.
 * - [detail]: optional extra context (device name, dock type, timezone id, …).
 * - [timestamp]: when the event was produced (epoch ms).
 */
@Serializable
data class SystemState(
    val event: String,
    val detail: String = "",
    val timestamp: Long,
)

/**
 * Package event reported by `trigger.app_installed` on its `package` data port.
 *
 * - [action]: `"installed"`, `"removed"` or `"replaced"`.
 * - [packageName]: the affected package, e.g. `com.example.app`.
 * - [timestamp]: when the event was produced (epoch ms).
 */
@Serializable
data class PackageEvent(
    val action: String,
    val packageName: String,
    val timestamp: Long,
)

/**
 * Media event reported by `trigger.media_button` and `trigger.media_mount`
 * on their `media` data port.
 *
 * - [event]: `"button"`, `"mounted"`, `"unmounted"` or `"ejected"`.
 * - [detail]: optional extra (media key name, mount path, …).
 * - [timestamp]: when the event was produced (epoch ms).
 */
@Serializable
data class MediaEvent(
    val event: String,
    val detail: String = "",
    val timestamp: Long,
)

/**
 * Result of a macro enable/disable action (`action.enable_macro` /
 * `action.disable_macro`) reported on its `state` data port.
 *
 * - [macroId]: the target macro id.
 * - [changed]: whether the control request was dispatched (false when the
 *   engine has no [com.example.ottomatic.core.service.MacroControl] handle).
 */
@Serializable
data class MacroControlState(
    val macroId: String,
    val changed: Boolean,
)

/**
 * Bluetooth radio state reported by the bluetooth action on its `state` port.
 *
 * - [enabled]: whether Bluetooth is now on.
 * - [changed]: whether the system accepted the change.
 */
@Serializable
data class BluetoothState(
    val enabled: Boolean,
    val changed: Boolean,
)

/**
 * Ringer mode reported by the ringer mode action on its `state` port.
 *
 * - [mode]: the resulting ringer mode.
 * - [changed]: whether the system accepted the change.
 */
@Serializable
data class RingerModeState(
    val mode: RingerMode,
    val changed: Boolean,
)

/**
 * Screen brightness reported by the brightness action on its `state` port.
 *
 * - [value]: resulting brightness index (0..255).
 * - [auto]: whether auto-brightness is now active.
 * - [changed]: whether the system accepted the change (requires WRITE_SETTINGS).
 */
@Serializable
data class BrightnessState(
    val value: Int,
    val auto: Boolean,
    val changed: Boolean,
)

/**
 * Screen-off timeout reported by the screen timeout action on its `state` port.
 *
 * - [ms]: resulting timeout in milliseconds.
 * - [changed]: whether the system accepted the change (requires WRITE_SETTINGS).
 */
@Serializable
data class ScreenTimeoutState(
    val ms: Int,
    val changed: Boolean,
)

/**
 * Auto-rotate state reported by the auto-rotate action on its `state` port.
 *
 * - [enabled]: whether accelerometer rotation is now on.
 * - [changed]: whether the system accepted the change (requires WRITE_SETTINGS).
 */
@Serializable
data class AutoRotateState(
    val enabled: Boolean,
    val changed: Boolean,
)

/**
 * Torch (flashlight) state reported by the flashlight action on its `state` port.
 *
 * - [enabled]: whether the torch is now on.
 * - [changed]: whether the system accepted the change (requires CAMERA).
 */
@Serializable
data class TorchState(
    val enabled: Boolean,
    val changed: Boolean,
)

/**
 * Result of the send-SMS action on its `state` data port.
 *
 * - [to]: the destination phone number.
 * - [body]: the message body sent.
 * - [sent]: whether the send call was accepted (requires SEND_SMS).
 */
@Serializable
data class SmsSent(
    val to: String,
    val body: String,
    val sent: Boolean,
)

/**
 * Result of the call action on its `state` data port.
 *
 * - [number]: the destination phone number.
 * - [initiated]: whether the call was placed (requires CALL_PHONE).
 */
@Serializable
data class CallInitiated(
    val number: String,
    val initiated: Boolean,
)
