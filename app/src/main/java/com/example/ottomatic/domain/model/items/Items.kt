package com.example.ottomatic.domain.model.items

import com.example.ottomatic.core.service.AudioStream
import com.example.ottomatic.core.service.DndLevel
import com.example.ottomatic.core.service.HttpMethod
import com.example.ottomatic.core.service.RingerMode
import com.example.ottomatic.core.service.VolumeMode
import com.example.ottomatic.domain.model.schema.DateTime
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
 *   [com.example.ottomatic.domain.model.ConversationRef]. Text rather than a nested
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
 *   [com.example.ottomatic.domain.model.WifiSsid].
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
 * clock-change, locale, shutdown, call) on their `state` data port.
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
 * A mail message, on `trigger.mail`'s `mail` port and `action.fetch_mail`'s
 * `messages` port.
 *
 * - [ref]: the durable handle `action.mail_update` acts on — see
 *   [com.example.ottomatic.domain.model.MailRef]. Text rather than a nested struct,
 *   so one `action.break` reaches it and it can be wired into a scalar port.
 * - [from] and [fromName] are separate on purpose: the address is what a filter or
 *   an `action.if` compares, the display name is what belongs in a notification.
 *   One field would put a `transform` in front of every use of the other.
 * - [body]: plain text, capped at [com.example.ottomatic.core.service.MailLimits.BODY_CHARS]
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
 * Result of `action.send_message` on its `state` data port.
 *
 * [opened] rather than "sent", and the word is the whole point of the node: no
 * messenger on Android lets a third-party app send a *new* message silently, so
 * this one opens the app with the recipient and text filled in and the person taps
 * Send. `action.reply_message` is the one that actually sends, and it can only ever
 * reply.
 *
 * [to] carries the **resolved** recipient rather than the stored
 * [com.example.ottomatic.domain.model.PhoneRef] spec, as [CallInitiated] does, so a
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
