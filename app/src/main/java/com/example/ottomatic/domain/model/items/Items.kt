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

/** A duration value, in milliseconds, flowing into `action.delay`'s `duration` port. */
@Serializable
data class DurationMillis(
    val millis: Long,
)
