package com.example.ottomatic.core.service

/**
 * Android-backed system operations exposed to actions without pulling
 * Android types into `engine/`. Implemented by [AndroidSystemServices] in `data/`.
 */
interface SystemServices {

    /** Posts a notification with [title] and [text]. Returns true on success. */
    fun notify(title: String, text: String): Boolean

    /** Toggles Wi-Fi. Returns the new state, or null if it could not be changed. */
    fun setWifi(enabled: Boolean): Boolean?

    /** Performs an HTTP request. */
    fun httpRequest(request: HttpRequest): HttpResponse

    /**
     * Adjusts an audio stream's volume. [mode] is one of `"up"`, `"down"`,
     * `"set"`, `"mute"` or `"unmute"`; [value] (0..100) is only used when
     * [mode] is `"set"` and is scaled to the stream's max index. Returns null
     * when the stream is unknown or the call fails.
     */
    fun setVolume(stream: String, mode: String, value: Int): VolumeResult?

    /**
     * Toggles Do-Not-Disturb. When [enabled] is true, [level] selects the
     * policy (`"priority"`, `"alarms"`, `"silence"`); when false, [level] is
     * ignored and all notifications are allowed. Returns null when the
     * `ACCESS_NOTIFICATION_POLICY` permission has not been granted or the call
     * fails.
     */
    fun setDnd(enabled: Boolean, level: String): DndResult?
}

data class HttpRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val body: String = "",
)

data class HttpResponse(
    val statusCode: Int,
    val body: String,
    val headers: Map<String, String> = emptyMap(),
)

/**
 * Result of a volume change via [SystemServices.setVolume].
 *
 * - [stream]: which audio stream was adjusted (`"media"`, `"ring"`, `"alarm"`,
 *   `"notification"`, `"system"`).
 * - [mode]: the requested change (`"up"`, `"down"`, `"set"`, `"mute"`,
 *   `"unmute"`).
 * - [volume]: resulting volume index.
 * - [maxVolume]: maximum index for the stream.
 * - [changed]: whether the call was accepted.
 */
data class VolumeResult(
    val stream: String,
    val mode: String,
    val volume: Int,
    val maxVolume: Int,
    val changed: Boolean,
)

/**
 * Result of a Do-Not-Disturb toggle via [SystemServices.setDnd].
 *
 * - [enabled]: whether DND is now active.
 * - [level]: policy in effect (`"priority"`, `"alarms"`, `"silence"` when
 *   enabled, or `"all"` when disabled).
 * - [changed]: whether the call was accepted.
 */
data class DndResult(
    val enabled: Boolean,
    val level: String,
    val changed: Boolean,
)
