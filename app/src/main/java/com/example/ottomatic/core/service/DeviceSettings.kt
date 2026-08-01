package com.example.ottomatic.core.service

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Closed sets of device-setting values shared by the [SystemServices] facade,
 * its Android implementation and the node config classes that drive it.
 *
 * These replace the free-form strings the facade used to accept (`"up"`,
 * `"set"`, `"priority"`, ...), where a typo silently degraded to a default or
 * returned null at runtime. Every `when` over them is now exhaustive, and the
 * config form's options are derived from the entries.
 *
 * They are `@Serializable` because node config classes use them directly; the
 * `@SerialName` of each entry is what gets persisted in a saved workflow.
 */

/** A two-state toggle. Reads better than a bare Boolean in a config form. */
@Serializable
enum class OnOff {
    @SerialName("on")
    ON,

    @SerialName("off")
    OFF,
    ;

    val enabled: Boolean get() = this == ON

    companion object {
        fun of(enabled: Boolean): OnOff = if (enabled) ON else OFF
    }
}

/** An audio stream whose volume can be adjusted. */
@Serializable
enum class AudioStream {
    @SerialName("media")
    MEDIA,

    @SerialName("ring")
    RING,

    @SerialName("alarm")
    ALARM,

    @SerialName("notification")
    NOTIFICATION,

    @SerialName("system")
    SYSTEM,
}

/**
 * Which sound `action.play_sound` plays. The three presets follow the device's
 * own defaults, so they need no permission and no chooser; [CUSTOM] plays the
 * sound identified by the node's `uri` config field.
 */
@Serializable
enum class SoundSource {
    @SerialName("notification")
    NOTIFICATION,

    @SerialName("ringtone")
    RINGTONE,

    @SerialName("alarm")
    ALARM,

    @SerialName("custom")
    CUSTOM,
}

/** How a volume change should be applied. */
@Serializable
enum class VolumeMode {
    @SerialName("up")
    UP,

    @SerialName("down")
    DOWN,

    @SerialName("set")
    SET,

    @SerialName("mute")
    MUTE,

    @SerialName("unmute")
    UNMUTE,
}

/** The device ringer mode. */
@Serializable
enum class RingerMode {
    @SerialName("normal")
    NORMAL,

    @SerialName("silent")
    SILENT,

    @SerialName("vibrate")
    VIBRATE,
}

/**
 * Do-Not-Disturb policy. [ALL] is only ever a *result* (DND off — everything
 * gets through), never a requested level.
 */
@Serializable
enum class DndLevel {
    @SerialName("priority")
    PRIORITY,

    @SerialName("alarms")
    ALARMS,

    @SerialName("silence")
    SILENCE,

    @SerialName("all")
    ALL,
}

/** HTTP method of an outgoing request. */
@Serializable
enum class HttpMethod {
    GET,
    POST,
    PUT,
    DELETE,
    ;

    /** Whether this method sends a request body. */
    val sendsBody: Boolean get() = this == POST || this == PUT
}
