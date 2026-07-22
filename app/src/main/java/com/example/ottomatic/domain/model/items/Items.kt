package com.example.ottomatic.domain.model.items

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

/** A scheduled trigger fire timestamp, exposed on `trigger.schedule`'s `fireTime` port. */
@Serializable
data class ScheduleFire(
    val firedAt: Long,
)

/** Request built by the HTTP action from its config + data inputs. */
@Serializable
data class HttpRequestItem(
    val method: String,
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
 * - [stream]: which audio stream was adjusted — `"media"`, `"ring"`,
 *   `"alarm"`, `"notification"` or `"system"`.
 * - [mode]: what was done — `"up"`, `"down"`, `"set"`, `"mute"` or `"unmute"`.
 * - [volume]: resulting volume index (0..[maxVolume]).
 * - [maxVolume]: maximum index for the stream.
 * - [changed]: whether the system accepted the change.
 */
@Serializable
data class VolumeState(
    val stream: String,
    val mode: String,
    val volume: Int,
    val maxVolume: Int,
    val changed: Boolean,
)

/**
 * Do-Not-Disturb state reported by the DND action on its `state` data port.
 *
 * - [enabled]: whether DND is now active.
 * - [level]: DND policy in effect — `"priority"`, `"alarms"` or `"silence"`
 *   when enabled, or `"all"` when disabled.
 * - [changed]: whether the system accepted the change (requires the
 *   `ACCESS_NOTIFICATION_POLICY` permission).
 */
@Serializable
data class DndState(
    val enabled: Boolean,
    val level: String,
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
 * A stopwatch tick reported by `trigger.stopwatch` on its `tick` data port.
 *
 * - [elapsedMs]: milliseconds elapsed since the stopwatch was started.
 * - [tickAt]: epoch ms at which this tick fired.
 */
@Serializable
data class StopwatchTick(
    val elapsedMs: Long,
    val tickAt: Long,
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
 * timezone, locale, date, shutdown, time-tick, call) on their `state` data port.
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
