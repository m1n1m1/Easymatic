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

/**
 * A two-state toggle plus [TOGGLE], which asks for the *opposite* of whatever is
 * true right now.
 *
 * Separate from [OnOff] rather than a third entry on it, because the third entry
 * is only answerable where the state can be read back. `action.wifi`,
 * `action.bluetooth` and `action.auto_rotate` write a setting and are never told
 * what it was; offering them a "Toggle" that silently did nothing would be worse
 * than not offering it. The torch has a reader behind it
 * ([DeviceState.isTorchOn]), so it gets the third option and they do not.
 */
@Serializable
enum class OnOffToggle {
    @SerialName("on")
    ON,

    @SerialName("off")
    OFF,

    @SerialName("toggle")
    TOGGLE,
    ;

    /**
     * What this choice means given [current], the state read back from the
     * device, or null when [TOGGLE] was asked for and nothing could be read —
     * which is the one case where there is no honest answer to invert.
     */
    fun resolve(current: Boolean?): Boolean? = when (this) {
        ON -> true
        OFF -> false
        TOGGLE -> current?.not()
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

/**
 * Which way round the screen is locked, for `action.screen_rotation`.
 *
 * **Four positions where `trigger.device_orientation` reports six**, and the two
 * that are missing are the point: face up and face down are resting positions of
 * the *device*, and no display rotation corresponds to either — a phone lying on
 * a desk still shows portrait or landscape. Offering them would be offering a
 * choice that could never be applied, which is the failure a picker over a closed
 * set is supposed to make impossible.
 *
 * The four that remain carry the trigger's names deliberately, and name the same
 * physical positions, so "when it is face down, turn the screen to landscape
 * left" is one vocabulary rather than two. [LANDSCAPE_LEFT] is the device turned
 * anticlockwise — its top edge toward the left — and is what Android itself calls
 * plain landscape; [LANDSCAPE_RIGHT] is the other one.
 *
 * Both halves measure from the device's **natural** orientation, which is
 * portrait on a phone and landscape on some tablets. That is the frame the
 * accelerometer axes use too, so where "portrait" is not upright the trigger and
 * this action are wrong together rather than disagreeing.
 */
@Serializable
enum class ScreenRotation {
    @SerialName("portrait")
    PORTRAIT,

    @SerialName("portrait_upside_down")
    PORTRAIT_UPSIDE_DOWN,

    @SerialName("landscape_left")
    LANDSCAPE_LEFT,

    @SerialName("landscape_right")
    LANDSCAPE_RIGHT,
}
