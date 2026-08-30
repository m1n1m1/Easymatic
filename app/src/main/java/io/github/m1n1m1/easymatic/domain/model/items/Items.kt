package io.github.m1n1m1.easymatic.domain.model.items

import io.github.m1n1m1.easymatic.core.service.AudioStream
import io.github.m1n1m1.easymatic.core.service.CalendarAvailability
import io.github.m1n1m1.easymatic.core.service.CalendarEventStatus
import io.github.m1n1m1.easymatic.core.service.DndLevel
import io.github.m1n1m1.easymatic.core.service.HttpMethod
import io.github.m1n1m1.easymatic.core.service.RingerMode
import io.github.m1n1m1.easymatic.core.service.ScreenRotation
import io.github.m1n1m1.easymatic.core.service.VolumeMode
import io.github.m1n1m1.easymatic.domain.model.schema.DateTime
import kotlinx.serialization.Serializable

/**
 * A received SMS, exposed as a typed data item on the `trigger.sms` node's
 * `sms` output port.
 */
@Serializable
data class SmsMessage(
    val sender: String,
    val body: String,
    val timestamp: DateTime,
)

/** A received notification, exposed on `trigger.notification`'s `notification` port. */
@Serializable
data class NotificationEvent(
    val packageName: String,
    val title: String,
    val text: String,
    val timestamp: DateTime,
)

/**
 * One message from a messenger app, exposed on `trigger.message`'s `message` port.
 *
 * Read out of the notification the app posted, which is the only channel any of them
 * offers a third-party app on the same phone — so nothing here is WhatsApp-shaped,
 * Signal-shaped or Telegram-shaped, and a fourth messenger needs no code.
 *
 * - [conversation] and [sender] are separate for [MailMessage]'s reason: in a group
 *   chat they are different things, and one field would put a transform in front of
 *   every use of the other. In a one-to-one chat they are usually the same string.
 * - [conversationId] is the durable handle `action.reply_message` acts on — see
 *   [io.github.m1n1m1.easymatic.domain.model.ConversationRef]. Text rather than a nested
 *   struct, so it can be wired into a scalar port. `trigger.message` also publishes
 *   it as a **port of its own**, so the commonest wiring in the whole family needs
 *   no `action.break`; it stays a field as well, so a macro that breaks the struct
 *   for the sender or the text still has it to hand.
 *   Named against [conversation] deliberately: that one is what the chat is *called*
 *   and belongs in a notification, this one is opaque and belongs only in a wire.
 * - [canReply] says whether the app attached a reply action to this notification.
 *   A named field rather than something to discover at run time, because it is what
 *   an `action.if` should branch on before wiring a reply that cannot work — an SMS
 *   app that offers none, or a notification with actions stripped by a launcher.
 * - [timestamp] is the **message's** own time, not the moment the notification was
 *   posted. A messenger reposts one notification per chat as the conversation grows,
 *   so the two drift apart exactly when the history matters.
 */
@Serializable
data class MessageEvent(
    val packageName: String,
    val appName: String = "",
    val conversation: String = "",
    val sender: String = "",
    val text: String = "",
    val isGroup: Boolean = false,
    val canReply: Boolean = false,
    val conversationId: String = "",
    val timestamp: DateTime,
)

/**
 * A `trigger.schedule` fire, exposed on its `fireTime` port.
 *
 * - [firedAt]: the moment this fire occurred.
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
    val firedAt: DateTime,
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
    val timestamp: DateTime,
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
 * - [timestamp]: when the transition was produced.
 */
@Serializable
data class GeofenceEvent(
    val triggerNodeId: String,
    val transition: String,
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float,
    val timestamp: DateTime,
)

/**
 * A Wi-Fi network being joined or left, reported by `trigger.wifi_network` on its
 * `network` data port.
 *
 * - [event]: discriminator — `"connected"` or `"disconnected"`.
 * - [ssid]: the network's name. A **named field rather than a `SystemState.detail`**,
 *   because the whole point of this trigger is what you do with the name: it goes
 *   straight into a notification template or an `action.if`, and `detail` would make
 *   that an untyped string alongside a stringly event.
 *   Blank when Android would not name the network — see
 *   [io.github.m1n1m1.easymatic.domain.model.WifiSsid].
 * - [timestamp]: when the transition was produced.
 */
@Serializable
data class WifiNetworkEvent(
    val event: String,
    val ssid: String = "",
    val timestamp: DateTime,
)

/**
 * A tag tap reported by `trigger.nfc` on its `tag` data port.
 *
 * - [tagId]: the tag's hardware id as uppercase hex. Always present, because it is
 *   what the tap *is* — a tag with no readable id never reaches a trigger.
 * - [tagName]: the name from the tag library, or blank for a tag that was never
 *   saved. A named field for [WifiNetworkEvent.ssid]'s reason, plus one of its own:
 *   with the trigger set to "any tag" this is how one macro tells "Desk" from "Car
 *   dock" in a single `action.if`, instead of needing a macro per sticker.
 * - [text]: the tag's NDEF content as text, or blank when it holds none. Free to
 *   read — the platform decodes it at discovery — so a tag can carry data as well
 *   as identity.
 * - [timestamp]: when the tag was tapped.
 */
@Serializable
data class NfcScan(
    val tagId: String,
    val tagName: String = "",
    val text: String = "",
    val timestamp: DateTime,
)

/**
 * A variable change reported by `trigger.variable_change` on its `variable` data port.
 *
 * - [name]: the variable name.
 * - [value]: the new string value.
 * - [timestamp]: when the change was produced.
 */
@Serializable
data class VariableChange(
    val name: String,
    val value: String,
    val timestamp: DateTime,
)

/**
 * A device/UI mode change reported by `trigger.mode_change` on its `mode` data port.
 *
 * - [mode]: the new mode — `"normal"` or `"night"`.
 * - [timestamp]: when the change was produced.
 */
@Serializable
data class ModeChange(
    val mode: String,
    val timestamp: DateTime,
)

/**
 * Generic system-state event reported by Tier 1 broadcast-receiver triggers
 * (wifi, bluetooth, airplane, headset, usb, dock, screen, ringer, power-save,
 * clock-change, locale, shutdown) on their `state` data port.
 *
 * - [event]: discriminator — `"enabled"`, `"disabled"`, `"connected"`,
 *   `"disconnected"`, `"on"`, `"off"`, `"plugged"`, `"unplugged"`, etc.
 * - [detail]: optional extra context (device name, dock type, timezone id, …).
 * - [timestamp]: when the event was produced.
 */
@Serializable
data class SystemState(
    val event: String,
    val detail: String = "",
    val timestamp: DateTime,
)

/**
 * A failed unlock, reported by `trigger.login_failed` on its `attempt` data port.
 *
 * - [attempts]: consecutive failed attempts including this one, or **-1** when the
 *   count could not be read. Not zero — a zero here would read as a successful
 *   unlock, which is the one thing this item never describes.
 * - [timestamp]: when the attempt failed.
 *
 * Deliberately not a [SystemState]. That struct's `detail` is text carrying
 * whatever the broadcast happened to know, and a count wired into a comparison has
 * to arrive as a number.
 */
@Serializable
data class LoginAttempt(
    val attempts: Int = -1,
    val timestamp: DateTime,
)

/**
 * Package event reported by `trigger.app_installed` on its `package` data port.
 *
 * - [action]: `"installed"`, `"removed"` or `"replaced"`.
 * - [packageName]: the affected package, e.g. `com.example.app`.
 * - [timestamp]: when the event was produced.
 */
@Serializable
data class PackageEvent(
    val action: String,
    val packageName: String,
    val timestamp: DateTime,
)

/**
 * Media event reported by `trigger.media_button` and `trigger.media_mount`
 * on their `media` data port.
 *
 * - [event]: `"button"`, `"mounted"`, `"unmounted"` or `"ejected"`.
 * - [detail]: optional extra (media key name, mount path, …).
 * - [timestamp]: when the event was produced.
 */
@Serializable
data class MediaEvent(
    val event: String,
    val detail: String = "",
    val timestamp: DateTime,
)

/**
 * A sensor or gesture event reported by the `trigger.device_*`, `trigger.shake`,
 * `trigger.proximity` and `trigger.light_level` nodes on their `reading` port.
 *
 * - [event]: discriminator — `"face_down"`, `"shake"`, `"double_tap"`,
 *   `"picked_up"`, `"wave"`, `"above"`, …
 * - [sensor]: which sensor produced it — `"accelerometer"`, `"proximity"` or
 *   `"light"`.
 * - [value]: the gesture's magnitude, where it has one — lux for a light
 *   threshold, peak m/s² for a shake, centimetres for proximity. Zero for
 *   gestures that carry no magnitude.
 * - [detail]: optional extra context.
 * - [timestamp]: when the event was produced.
 *
 * [value] is a number rather than text so "only when it is darker than 5 lux" is
 * a single `action.if`. Carrying it in a [SystemState.detail]-style string would
 * put a `transform.convert` in the wire before every numeric comparison.
 */
@Serializable
data class SensorReading(
    val event: String,
    val sensor: String,
    val value: Float = 0f,
    val detail: String = "",
    val timestamp: DateTime,
)

/**
 * Result of a macro enable/disable action (`action.enable_macro` /
 * `action.disable_macro`) reported on its `state` data port.
 *
 * - [macroId]: the target macro id.
 * - [changed]: whether the control request was dispatched (false when the
 *   engine has no [io.github.m1n1m1.easymatic.core.service.MacroControl] handle).
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
 * Screen rotation reported by the screen rotation action on its `state` port.
 *
 * - [rotation]: which way round the screen is now held.
 * - [changed]: whether the system accepted the change (requires WRITE_SETTINGS).
 *
 * There is deliberately no "auto-rotation is now off" field: a change the system
 * accepted always turned it off, so the flag would only ever repeat [changed].
 */
@Serializable
data class ScreenRotationState(
    val rotation: ScreenRotation,
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
 * A mail message, on `trigger.mail`'s `mail` port and `action.fetch_mail`'s
 * `messages` port.
 *
 * - [ref]: the durable handle `action.mail_update` acts on — see
 *   [io.github.m1n1m1.easymatic.domain.model.MailRef]. Text rather than a nested struct,
 *   so one `action.break` reaches it and it can be wired into a scalar port.
 * - [from] and [fromName] are separate on purpose: the address is what a filter or
 *   an `action.if` compares, the display name is what belongs in a notification.
 *   One field would put a `transform` in front of every use of the other.
 * - [body]: plain text, capped at [io.github.m1n1m1.easymatic.core.service.MailLimits.BODY_CHARS]
 *   **as it is extracted**, so a large HTML mail is never materialised whole.
 *   [bodyTruncated] says whether that happened, so a macro can branch on it rather
 *   than a notification quietly showing half a message.
 * - [receivedAt] is a [DateTime], not a Long: a timestamp is a DateTime everywhere.
 */
@Serializable
data class MailMessage(
    val ref: String,
    val from: String,
    val fromName: String = "",
    val to: String = "",
    val subject: String = "",
    val body: String = "",
    val bodyTruncated: Boolean = false,
    val unread: Boolean = true,
    val hasAttachments: Boolean = false,
    val folder: String = "INBOX",
    val accountId: String = "",
    val receivedAt: DateTime,
)

/**
 * Result of `action.send_mail` on its `state` data port.
 *
 * [SmsSent]'s shape plus an [error], and the extra field is the whole difference
 * between the two: an SMS send fails for essentially one reason, where a mail send
 * fails for a wrong password, an unreachable host, a provider that has not had
 * IMAP switched on, a malformed recipient or no network — and "which one" is the
 * entire question when a macro stops working. Blank when [sent].
 */
@Serializable
data class MailSent(
    val to: String,
    val subject: String,
    val sent: Boolean,
    val error: String = "",
)

/**
 * Result of `action.mail_update` on its `state` data port.
 *
 * [changed] is false both when the server refused and when the reference named
 * nothing — a message that has been moved or expunged since the macro was handed
 * it. Those are one answer on purpose: neither is something the graph can retry,
 * and [error] says which it was.
 */
@Serializable
data class MailFlagged(
    val ref: String,
    val op: String,
    val changed: Boolean,
    val error: String = "",
)

/**
 * One occurrence of one appointment, on `action.calendar_query`'s `events` list and
 * `value.calendar_next`'s output.
 *
 * [MailMessage]'s shape and its conventions, with four things worth reading twice:
 *
 * - [ref] is **text**, not a nested struct, so one `action.break` reaches it and it wires
 *   straight into `action.calendar_update`'s scalar `ref` port. It names *this occurrence*
 *   rather than the series — see
 *   [CalendarEventRef][io.github.m1n1m1.easymatic.domain.model.CalendarEventRef], where the
 *   difference between deleting today's stand-up and deleting every stand-up lives.
 * - [startsAt] and [endsAt] are [DateTime]s, already shifted out of the provider's UTC
 *   for an all-day appointment, so a comparison against `value.now` means what it looks
 *   like. [durationMinutes] beside them is a plain number, because **a duration is not a
 *   DateTime** — `ScheduleFire.elapsedMs`' rule.
 * - [endsAt] is **exclusive**, and for an all-day appointment it is midnight on the
 *   following day. That is the provider's own convention, kept rather than tidied away,
 *   because it makes `start <= now < end` correct for both kinds of appointment at once.
 * - [calendar] and [calendarRef] are separate for [MailMessage]'s `from`/`fromName`
 *   reason: the name belongs in a notification, the spec belongs in a wire. Wiring
 *   [calendarRef] into another node's Calendar field is what lets a macro find an
 *   appointment in one calendar and file something beside it.
 */
@Serializable
data class CalendarEvent(
    val ref: String,
    val title: String = "",
    val description: String = "",
    val location: String = "",
    val startsAt: DateTime,
    val endsAt: DateTime,
    val allDay: Boolean = false,
    val durationMinutes: Long = 0,
    val calendar: String = "",
    val calendarRef: String = "",
    val organiser: String = "",
    val availability: CalendarAvailability = CalendarAvailability.BUSY,
    val status: CalendarEventStatus = CalendarEventStatus.CONFIRMED,
    val recurring: Boolean = false,
    /** Minutes before the start of the earliest reminder, or -1 when there is none. */
    val reminderMinutes: Int = -1,
)

/**
 * Result of `action.calendar_add` on its `state` data port.
 *
 * [MailSent]'s shape, plus [ref] — which is the point of the node answering at all: a
 * macro that creates an appointment and then wants to change or cancel it later has
 * nothing else to hold on to. Blank when nothing was created.
 */
@Serializable
data class CalendarWritten(
    val ref: String,
    val title: String,
    val created: Boolean,
    val error: String = "",
)

/**
 * Result of `action.calendar_update` on its `state` data port.
 *
 * [MailFlagged]'s shape and its reasoning — [changed] is false both when the provider
 * refused and when the reference named an appointment that has since been deleted, and
 * [error] says which — plus [scope], which is the one extra field and earns its place:
 * this node's most consequential setting is whether it touched one occurrence or a whole
 * series, and a receipt that cannot say leaves somebody to work it out from the calendar
 * afterwards.
 */
@Serializable
data class CalendarChanged(
    val ref: String,
    val op: String,
    val scope: String,
    val changed: Boolean,
    val error: String = "",
)

/**
 * Result of `action.light_control` on its `state` data port.
 *
 * [MailFlagged]'s shape and its reasoning: [changed] is false both when the bridge
 * refused and when the reference named nothing — a bulb unpaired from the bridge
 * since the macro was written. Those are one answer on purpose, because neither is
 * something the graph can retry, and [error] says which it was.
 *
 * [target] is echoed back so a `for-each` over several lights can tell one receipt
 * from another.
 */
@Serializable
data class LightChanged(
    val target: String,
    val op: String,
    val changed: Boolean,
    val error: String = "",
)

/**
 * Result of `action.light_scene` on its `state` data port. [LightChanged]'s shape,
 * field for field, and for the same reason: the node takes an operation, so the
 * receipt has to say which one it carried out.
 *
 * [changed] rather than "activated", because two of the three operations do not
 * activate anything — a `false` after a successful turn-off would read to an
 * `action.if` as a failure.
 */
@Serializable
data class SceneChanged(
    val scene: String,
    val op: String,
    val changed: Boolean,
    val error: String = "",
)

/**
 * One light or group as `action.light_state` read it, on its `state` data port.
 *
 * [target] is echoed back so it can be fed straight into `action.light_control` —
 * the reason [MailMessage.ref] is text rather than a nested struct.
 *
 * [colour] is **hex text, not a number**, for three reasons pointing the same way:
 * `Item.asText()` renders it into a notification unchanged, `action.if` compares it
 * as written, and it wires straight back into Control Light's Colour field with no
 * `transform.convert` in between. Blank for a bulb with no colour gamut, and for a
 * group — a group reports aggregate on-ness and brightness and nothing else.
 *
 * [brightness] is a percentage carried as a `Double` because the bridge reports
 * fractions, and rounding here would make "has it changed?" answer wrongly at the
 * edges.
 *
 * [reachable] costs a second request — the bridge keeps connectivity on a resource
 * of its own rather than on the light — and is worth it, because "is that lamp
 * actually powered, or did somebody flip the wall switch?" is the question this
 * node exists to answer. False for a group, where there is nothing to base it on.
 *
 * [found] and [error] are the pair that stops "the light is off" and "the bridge
 * was unreachable" looking the same to an `action.if`. Everything above them is
 * meaningless when [found] is false.
 */
@Serializable
data class LightState(
    val target: String,
    val name: String = "",
    val on: Boolean = false,
    val brightness: Double = 0.0,
    val colour: String = "",
    val kelvin: Int = 0,
    val reachable: Boolean = false,
    val found: Boolean = false,
    val error: String = "",
)

/**
 * What `trigger.ha_state` emits when a Home Assistant entity changes.
 *
 * [state] is Home Assistant's own string — `on`, `off`, `21.4`, `home`, `playing` — and
 * is deliberately left as text rather than converted here. What it means depends
 * entirely on the entity, and `transform.convert` is the visible place a conversion
 * belongs; autocast drops one into the wire when a numeric port needs it.
 *
 * [previousState] is what makes "when the door *opens*" expressible rather than "while
 * the door is open": without it, an attribute-only change and a real transition are
 * indistinguishable downstream. It is blank for an entity that has just appeared, which
 * happens to every entity on the first restart after adding an integration.
 *
 * [attributes] is compact JSON, forced by the payload being stringly and right anyway:
 * a light's `brightness`, a media player's `media_title` and a climate entity's
 * `current_temperature` all live there, and `transform.json_read` walks them. That is
 * `action.http`'s road.
 */
@Serializable
data class HaStateChange(
    val entityId: String,
    val name: String = "",
    val state: String = "",
    val previousState: String = "",
    /** `°C`, `%`, `kWh`. Blank for anything that is not a measurement. */
    val unit: String = "",
    /** Every attribute, as compact JSON, for `transform.json_read`. */
    val attributes: String = "",
    val changedAt: DateTime,
)

/**
 * What `trigger.ha_event` emits when a named event fires on the hub's bus.
 *
 * [data] is the event's own payload as compact JSON, and is the whole point of the node:
 * a `zha_event` carries the button and the command, a `tag_scanned` carries the tag id,
 * and a custom event carries whatever the automation that fired it put there. There is
 * no schema to model — it is different for every event type in existence — so it arrives
 * as text and is read with `transform.json_read`.
 *
 * [origin] is `LOCAL` or `REMOTE`, which distinguishes something that happened on the
 * hub from something that arrived through its cloud connection.
 */
@Serializable
data class HaEvent(
    val eventType: String,
    /** The event's `data` object, as compact JSON. */
    val data: String = "",
    val origin: String = "",
    val firedAt: DateTime,
)

/**
 * Receipt from `action.ha_service` on its `state` data port.
 *
 * [called] rather than `changed`, and the difference is honest rather than pedantic:
 * Home Assistant accepts a service call and reports success **without saying whether
 * anything moved**. `LightChanged.changed` can promise more because the light nodes read
 * a state back; here there is nothing to read, so claiming a change would be the one
 * thing this integration must not do.
 *
 * [response] is a `return_response` payload as JSON, or blank — most services return
 * nothing at all, and the few that do (a weather forecast, a calendar query) are the
 * reason the field exists.
 */
@Serializable
data class HaServiceCalled(
    val service: String,
    val target: String = "",
    val called: Boolean,
    val response: String = "",
    val error: String = "",
)

/**
 * What `trigger.mqtt_message` emits when a message arrives on a watched topic.
 *
 * [topic] is the **exact** topic the message was published to and never the filter that
 * matched it, which is the field the node exists for: a macro watching
 * `zigbee2mqtt/+/action` needs to know which device pressed a button, and the filter it
 * configured cannot say.
 *
 * [payload] is the bytes as text, unparsed, on [HaEvent.data]'s reasoning — what a payload
 * means belongs entirely to whatever published it, and `transform.json_read` or
 * `transform.convert` is the visible place a reading belongs.
 *
 * [retained] is the field worth having and the one nobody expects. A retained message is
 * one the **broker** had stored and handed over on subscribe, so it may describe something
 * that happened months ago. That distinction is invisible in the payload and decides
 * whether a macro should act on it, which is why it is a port rather than a detail.
 */
@Serializable
data class MqttMessage(
    val topic: String,
    val payload: String = "",
    val retained: Boolean = false,
    /** 0, 1 or 2 — the delivery guarantee this message actually arrived under. */
    val qos: Int = 0,
    val receivedAt: DateTime,
)

/**
 * Receipt from `action.mqtt_publish` on its `state` data port.
 *
 * [published] rather than `delivered`, and the distinction is the protocol's rather than
 * this app's caution: MQTT has **no subscriber acknowledgement of any kind**. At QoS 0 this
 * means the bytes reached the socket and at QoS 1 or 2 that the broker acknowledged them,
 * and at every level a message published to a topic nobody is listening on succeeds
 * completely and by design. That is [HaServiceCalled.called]'s sentence one layer further
 * down: claiming more than the wire says is the one thing this must not do.
 */
@Serializable
data class MqttPublished(
    val topic: String,
    val published: Boolean,
    val error: String = "",
)

/**
 * Receipt from `action.file_write`, `action.file_delete` and `action.file_transfer`.
 *
 * One struct for the three of them, on `SystemState`'s precedent: they answer the same
 * question — did this happen, to what, and if not why — so three near-identical structs
 * would only be three places to edit when a field is added.
 *
 * [name] is the field that has to be here and would not be obvious: the Storage Access
 * Framework **renames silently**. Asked to create `notes.txt` where one already exists
 * it produces `notes (1).txt` and reports success, and a mime type inferred from an
 * extension can have another appended. So the name a macro asked for is not necessarily
 * the name that now exists, and this is what the macro reads to find out.
 *
 * [changed] `false` with a blank [error] is not a failure — it is `SKIP` declining to
 * overwrite, or a delete of something that was already gone. That is
 * `LightChanged.changed`'s distinction, for its reason.
 */
@Serializable
data class FileResultItem(
    val changed: Boolean,
    /** Where it ended up, which is not where it was asked for if the name changed. */
    val path: String = "",
    val name: String = "",
    val error: String = "",
)

/**
 * What `action.file_info` found out about one path.
 *
 * [exists] is deliberately its own field beside [error], because "there is no file
 * there" is an **answer** where "I could not find out" is a failure, and a macro
 * branching on the two needs them apart. Collapsing them onto one value is most of why
 * there is no `value.file_exists`.
 *
 * [sizeBytes] and [modifiedEpochMs] are **-1 when unknown, never 0**. A document
 * provider may leave either column out — cloud-backed ones routinely do — and a 0 there
 * would be a lie a macro would act on, reading an unmeasured file as an empty one.
 */
@Serializable
data class FileInfoItem(
    val exists: Boolean,
    val path: String,
    val name: String = "",
    val isFolder: Boolean = false,
    val sizeBytes: Long = -1,
    val modified: DateTime? = null,
    val error: String = "",
)

/**
 * One picture, as `trigger.image_saved`, `value.latest_image` and `action.image_list` all
 * see it.
 *
 * **[uri] and [path] are both here and neither is redundant**, which looks like the
 * duplication this file argues against everywhere else and is not. A picture genuinely has
 * two addresses that reach different things: [uri] names the row and is what every image
 * node can open, while [path] is what `action.ai_describe` and the `action.file_*` nodes
 * take — and each is missing exactly where the other works. `MediaStore`'s path column is
 * deprecated and blank on some phones, and even where it is present scoped storage will not
 * let `java.io.File` open it; a content URI, meanwhile, means nothing to a node built on
 * `FilePath`. Carrying both is what makes "resize the photo I just took and mail it" a
 * graph somebody can actually draw. `ImageRef` reads either.
 *
 * [path] is blank rather than absent when the collection does not say, so a macro testing
 * it gets a value rather than a null it cannot compare.
 *
 * [width], [height] and [sizeBytes] are **-1 when unknown, never 0**, on [FileInfoItem]'s
 * rule and for its reason: a zero here is a lie a macro acts on.
 */
@Serializable
data class ImageItem(
    /** The `content://` row. Always known for a picture that exists. */
    val uri: String = "",
    /** Absolute filesystem path, when the collection still reports one. */
    val path: String = "",
    val name: String = "",
    /** The folder as a person recognises it — `DCIM/Camera`, `Pictures/Screenshots`. */
    val folder: String = "",
    val mimeType: String = "",
    val width: Int = -1,
    val height: Int = -1,
    val sizeBytes: Long = -1,
    /** When the shutter fired. Null when the file does not say — a screenshot does not. */
    val takenAt: DateTime? = null,
    /** When this phone learned about the picture. Always known. */
    val addedAt: DateTime? = null,
)

/**
 * What `action.image_info` found out about one picture — the collection's columns and the
 * file's own metadata in one struct, because nobody asking "what camera took this?" is
 * thinking about which of the two answers it.
 *
 * Flat rather than nesting an [ImageItem], deliberately: one `action.break` then reaches
 * every field. A nested struct would put a second `action.break` between the user and the
 * width of their photo.
 *
 * [exists] is its own field beside [error], on [FileInfoItem]'s rule.
 *
 * **[hasLocation] gates the coordinates because `0.0, 0.0` is a real place** — it is in the
 * Gulf of Guinea — so there is no value of [latitude] that can mean "no location".
 * [locationHidden] is the third state that pair still cannot express: the picture *has* a
 * location and Easymatic is not permitted to read it, which from Android 10 is what a
 * missing `ACCESS_MEDIA_LOCATION` means. Without it, "this photo was taken nowhere" and "I
 * may not tell you where" would be the same answer.
 *
 * [exposureTime], [fNumber] and [focalLength] stay **text**, because what somebody wants to
 * read is `1/250` and `f/1.8`. Rendering either as a number picks one spelling and loses the
 * other, and `transform.convert` bridges it for anybody who wants to compare.
 */
@Serializable
data class ImageDetailsItem(
    val exists: Boolean,
    val uri: String = "",
    val path: String = "",
    val name: String = "",
    val folder: String = "",
    val mimeType: String = "",
    val width: Int = -1,
    val height: Int = -1,
    val sizeBytes: Long = -1,
    val takenAt: DateTime? = null,
    val addedAt: DateTime? = null,
    /** Clockwise rotation a viewer should apply: 0, 90, 180, 270, or -1 when unstated. */
    val orientationDegrees: Int = -1,
    val cameraMake: String = "",
    val cameraModel: String = "",
    val isoSpeed: Int = -1,
    val exposureTime: String = "",
    val fNumber: String = "",
    val focalLength: String = "",
    val description: String = "",
    val hasLocation: Boolean = false,
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    /** The picture has a location this app is not permitted to read. */
    val locationHidden: Boolean = false,
    val error: String = "",
)

/**
 * Receipt from `action.image_edit`, `action.image_move`, `action.image_delete` and
 * `action.image_metadata`.
 *
 * [FileResultItem]'s shape and its reasoning — one struct for four nodes answering the same
 * question — with one field it has no counterpart for.
 *
 * **[needsConfirmation] exists because Android has an outcome `changed`-plus-[error] cannot
 * express.** From Android 11, changing a picture another app saved needs the person holding
 * the phone to tap Allow. So "there was nobody to ask" is a state worth **retrying later**,
 * where "you said no" and "the file is gone" are not — and a macro can branch on the
 * difference. Folding it into [error] would make a locked screen indistinguishable from a
 * refusal, which is the collapse [FileInfoItem.exists] exists to prevent, one node along.
 *
 * Flat rather than nesting an [ImageItem], on [ImageDetailsItem]'s reasoning: a nested one
 * would make reading the path of the file you just wrote a *second* `action.break`, which is
 * the commonest thing anybody does after a write. [width] and [height] are here because an
 * edit changes them and a macro chaining a second edit needs to know.
 *
 * [uri] and [name] both matter after a write and for different reasons: MediaStore
 * **renames silently** on a collision, producing `photo (1).jpg` and reporting success, so
 * the name asked for is not necessarily the name that now exists — [FileResultItem.name]'s
 * lesson, which bites identically here.
 */
@Serializable
data class ImageResultItem(
    val changed: Boolean,
    val uri: String = "",
    val path: String = "",
    val name: String = "",
    val width: Int = -1,
    val height: Int = -1,
    val sizeBytes: Long = -1,
    /** Android wanted the user to confirm and there was no way to ask. Worth retrying. */
    val needsConfirmation: Boolean = false,
    val error: String = "",
)

/**
 * A recording that exists, as `trigger.recording_saved` reports it.
 *
 * A path and no `uri`, which is the one field [ImageItem] has that this deliberately does
 * not. A photo is a row in a collection every gallery reads, so its `content://` handle is
 * the durable way to name it; a recording is a file, made by this app, in a folder somebody
 * chose — so the path is not merely enough, it is the thing the six `action.file_*` nodes
 * and every mail attachment already speak.
 *
 * [durationMs] and [sizeBytes] are **-1 when unknown, never 0**, on [FileInfoItem]'s rule:
 * a zero here reads as "it recorded nothing", which is a different and much more alarming
 * answer than "the file does not say".
 */
@Serializable
data class RecordingItem(
    val path: String = "",
    val name: String = "",
    /** The folder as a person recognises it, or blank for the app's own storage. */
    val folder: String = "",
    val mimeType: String = "",
    val durationMs: Long = -1,
    val sizeBytes: Long = -1,
    /** When the recording finished. */
    val recordedAt: DateTime? = null,
)

/**
 * Receipt from `action.record_audio` and `action.record_stop`.
 *
 * [FileResultItem]'s shape with the two facts only a recording has, and flat rather than
 * nesting a [RecordingItem] on [ImageResultItem]'s reasoning: reading the path of the file
 * you just made is the commonest thing anybody does next, and a nested struct would put a
 * second `action.break` in front of it.
 *
 * **[changed] `false` with a blank [error] cannot happen here**, which is where it parts
 * company with [FileResultItem]. There, a false-with-no-error is `SKIP` declining to
 * overwrite — a real outcome. A recording that produced no file always has something to
 * say: nothing was running, the microphone was held by another app, the grant is gone.
 *
 * `action.record_start` returns none of this on purpose. It has no file yet and no length,
 * so a receipt from it could only be a `changed` that meant "began" while the same field on
 * these two means "finished" — see that node.
 */
@Serializable
data class RecordingResultItem(
    val changed: Boolean,
    /** Where it ended up, which is not where it was asked for if the name changed. */
    val path: String = "",
    val name: String = "",
    val durationMs: Long = -1,
    val sizeBytes: Long = -1,
    val error: String = "",
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

/**
 * A phone call starting, connecting or ending, on `trigger.call_state`'s and
 * `trigger.call_ended`'s `call` data port, and read back by `value.current_call`.
 *
 * One struct for the cellular radio *and* for every app that places calls, which is the
 * whole point: "when a call ends, turn the music back on" is one macro whether the call
 * came in on the SIM, on Teams or on WhatsApp. `CallSessions` merges the two channels
 * before anything reaches here, so nothing downstream has to know which it was.
 *
 * - [state]: `"ringing"`, `"active"` or `"ended"`.
 * - [caller]: who is on the other end, as the call app printed it — a contact name where
 *   there is one, otherwise whatever it showed instead. Empty when the app named nobody.
 *   There is deliberately no separate `number` field: telephony discloses one only under
 *   `READ_CALL_LOG`, which Easymatic does not ask for, and a field that is permanently
 *   empty is worse than an absent one.
 * - [appName] / [packageName]: which app the call is in. For a cellular call this is the
 *   phone's own dialer.
 * - [incoming]: false for a call this phone placed. Derived from a connect with no ring
 *   before it, which is the only signal either channel gives.
 * - [answered] and [durationSeconds] are meaningful **only** once [state] is `"ended"`;
 *   both are 0/false before that, and both stay 0/false for a call that was never picked
 *   up, which is what tells a missed call from a short one.
 */
@Serializable
data class CallEvent(
    val state: String,
    val caller: String = "",
    val appName: String = "",
    val packageName: String = "",
    val incoming: Boolean = true,
    val video: Boolean = false,
    val answered: Boolean = false,
    val durationSeconds: Int = 0,
    val timestamp: DateTime,
)

/**
 * Result of `action.reply_message` on its `state` data port.
 *
 * [MailSent]'s shape and its reasoning: [sent] alone cannot say *why*, and here the
 * reasons are unusually varied — notification access switched off, a reference that
 * parses as nothing, a messenger that offers no reply action, and the common one,
 * a conversation whose notification is gone because the user opened the chat.
 *
 * [conversationId] echoes the handle back rather than a display name, so a
 * `for-each` replying to several chats can tell one receipt from another.
 */
@Serializable
data class MessageReplied(
    val conversationId: String,
    val text: String,
    val sent: Boolean,
    val error: String = "",
)

/**
 * Result of `action.notification_action` on its `state` data port.
 *
 * [MailFlagged]'s shape, field for field: the node takes an operation, so the
 * receipt has to say which one it carried out, and [changed] is false both when the
 * app refused and when the reference named nothing.
 */
@Serializable
data class NotificationActed(
    val conversationId: String,
    val op: String,
    val changed: Boolean,
    val error: String = "",
)

/**
 * Result of `action.notify_cancel` on its `state` data port.
 *
 * [NotificationActed]'s shape without the `op`, since there is only one thing this
 * node does. [changed] is false both when the tag named nothing and when nothing was
 * named at all — the two are told apart by [error], not by a second branch, because
 * "the notification was already gone" is the ordinary case rather than a failure.
 */
@Serializable
data class NotificationRemoved(
    val tag: String,
    val changed: Boolean,
    val error: String = "",
)

/**
 * Result of `action.send_message` on its `state` data port.
 *
 * [opened] rather than "sent", and the word is the whole point of the node: no
 * messenger on Android lets a third-party app send a *new* message silently, so
 * this one opens the app with the recipient and text filled in and the person taps
 * Send. `action.reply_message` is the one that actually sends, and it can only ever
 * reply.
 *
 * [to] carries the **resolved** recipient rather than the stored
 * [io.github.m1n1m1.easymatic.domain.model.PhoneRef] spec, as [CallInitiated] does, so a
 * `contact:` reference never leaks onto a wire as an opaque string.
 */
@Serializable
data class MessageComposed(
    val app: String,
    val to: String,
    val text: String,
    val opened: Boolean,
    val error: String = "",
)

/**
 * What one media player is playing, as `value.now_playing` reports it on its `track`
 * data port.
 *
 * The graph's copy of [io.github.m1n1m1.easymatic.core.service.NowPlaying]. It exists
 * separately for the boundary rather than for tidiness — `core` may not import `domain`
 * — which is the same reason `RecordingRecord` and `RecordingItem` are two types.
 *
 * [durationMs] and [positionMs] are **-1 when the player does not say, never 0**. Zero is
 * an ordinary position for a track to be at, so a zero standing in for "unknown" would be
 * a lie a comparison cannot see through.
 *
 * [app] is the package name and [appName] the label a person recognises: the first is what
 * an `action.if` compares against and what the app picker stores, the second is what a
 * notification should print.
 */
@Serializable
data class NowPlayingItem(
    val app: String = "",
    val appName: String = "",
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val playing: Boolean = false,
    val durationMs: Long = -1,
    val positionMs: Long = -1,
)

/**
 * A playback change reported by `trigger.media_playback` on its `playback` data port.
 *
 * Its own struct rather than [NowPlayingItem] with a field bolted on, for the reason
 * [RecordingItem] is not [RecordingResultItem]: a state answers "what is true now" and an
 * event answers "here is what just happened". [event] is the fact the state has nowhere to
 * carry, and [timestamp] is the fact only an event has at all.
 *
 * [event] is `"started"`, `"paused"`, `"stopped"` or `"track_changed"` — the lowercased
 * [io.github.m1n1m1.easymatic.core.service.PlaybackKind], matching what every other trigger
 * puts in a discriminator field so an `action.if` over it reads the same way everywhere.
 */
@Serializable
data class PlaybackEvent(
    val event: String,
    val app: String = "",
    val appName: String = "",
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val playing: Boolean = false,
    val durationMs: Long = -1,
    val timestamp: DateTime,
)

/**
 * Result of `action.media_control` and `action.media_seek` on their `state` data port.
 *
 * One struct for both nodes, because both answer the same question — *did the player get
 * it?* — and a second type differing by one field would only invite the two to drift.
 *
 * [changed] means the command was **delivered**, not that the player obeyed it: transport
 * controls are one-way and there is no acknowledgement to wait for, so claiming more would
 * be inventing it.
 *
 * [positionMs] is -1 for everything a seek did not produce, on [NowPlayingItem]'s rule.
 */
@Serializable
data class MediaControlState(
    val command: String,
    val app: String = "",
    val changed: Boolean = false,
    val positionMs: Long = -1,
    val error: String = "",
)
