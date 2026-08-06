package com.example.ottomatic.core.service

import kotlinx.coroutines.flow.StateFlow

/**
 * Android-backed system operations exposed to actions without pulling
 * Android types into `engine/`. Implemented by [AndroidSystemServices] in `data/`.
 */
@Suppress("TooManyFunctions") // Facade over many independent Android subsystems.
interface SystemServices {

    /** Posts a notification with [title] and [text]. Returns true on success. */
    fun notify(title: String, text: String): Boolean

    /** Toggles Wi-Fi. Returns the new state, or null if it could not be changed. */
    fun setWifi(enabled: Boolean): Boolean?

    /** Performs an HTTP request. */
    fun httpRequest(request: HttpRequest): HttpResponse

    /**
     * Adjusts an audio [stream]'s volume. [value] (0..100) is only used when
     * [mode] is [VolumeMode.SET] and is scaled to the stream's max index.
     * Returns null when the call fails.
     */
    fun setVolume(stream: AudioStream, mode: VolumeMode, value: Int): VolumeResult?

    /**
     * Toggles Do-Not-Disturb. When [enabled] is true, [level] selects the
     * policy; when false, [level] is ignored and all notifications are allowed.
     * Returns null when the `ACCESS_NOTIFICATION_POLICY` permission has not been
     * granted or the call fails.
     */
    fun setDnd(enabled: Boolean, level: DndLevel): DndResult?

    /**
     * Toggles Bluetooth. Returns null when Bluetooth is unavailable or the call
     * fails (e.g. missing `BLUETOOTH_CONNECT` permission on API 31+).
     */
    fun setBluetooth(enabled: Boolean): BluetoothResult?

    /**
     * Sets the ringer [mode]. Returns null on failure (e.g. missing
     * `ACCESS_NOTIFICATION_POLICY` for [RingerMode.SILENT] on API 21+).
     */
    fun setRingerMode(mode: RingerMode): RingerResult?

    /**
     * Sets the screen brightness. [value] (0..255) is used when [auto] is
     * false; when [auto] is true the device switches to auto-brightness and
     * [value] is ignored. Returns null on failure (requires
     * `WRITE_SETTINGS`).
     */
    fun setBrightness(value: Int, auto: Boolean): BrightnessResult?

    /**
     * Sets the screen-off timeout in milliseconds. Returns null on failure
     * (requires `WRITE_SETTINGS`).
     */
    fun setScreenTimeout(ms: Int): ScreenTimeoutResult?

    /**
     * Toggles accelerometer auto-rotation. Returns null on failure (requires
     * `WRITE_SETTINGS`).
     */
    fun setAutoRotate(enabled: Boolean): AutoRotateResult?

    /**
     * Toggles the camera torch. Returns null when no camera with a flash unit
     * is available or the call fails (requires `CAMERA`).
     */
    fun setTorch(enabled: Boolean): TorchResult?

    /**
     * Vibrates the device for [durationMs] (or [pattern] long-off-long… pairs
     * in ms when non-empty). Returns false on failure.
     */
    fun vibrate(durationMs: Int, pattern: List<Long> = emptyList()): Boolean

    /**
     * Plays a sound as described by [request].
     *
     * When [SoundRequest.waitForCompletion] is true this suspends until
     * playback ends, and cancelling the caller stops the sound. When it is
     * false the sound outlives the call — until its own end, until
     * [SoundRequest.maxMs], or until [stopSounds].
     *
     * Returns false when the sound cannot be played — no default set for the
     * chosen type, a blank or unreadable uri, a start offset at or past the end
     * of the sound, or a media-library sound without `READ_MEDIA_AUDIO`
     * (API 33+) / `READ_EXTERNAL_STORAGE` below it. The presets need no
     * permission.
     */
    suspend fun playSound(request: SoundRequest): Boolean

    /**
     * Stops every sound started by [playSound], returning how many were
     * playing. A caller waiting on one is released rather than cancelled: the
     * sound ends, the workflow carries on.
     */
    fun stopSounds(): Int

    /**
     * Whether any sound is playing right now, so a UI can offer to stop it —
     * and only while there is something to stop.
     */
    val soundPlaying: StateFlow<Boolean>

    /**
     * Launches the app with [packageName] (its main launcher activity).
     *
     * [LaunchOutcome.NoSuchApp] when it is not installed or has no launcher
     * activity; [LaunchOutcome.Blocked] when Ottomatic has no visible window and
     * has not been granted `SYSTEM_ALERT_WINDOW`, which is the only way this can
     * fail while looking as though it worked.
     */
    fun launchApp(packageName: String): LaunchOutcome

    /**
     * Opens [url] in the default handler (browser/app via intent).
     *
     * [LaunchOutcome.NoHandler] when nothing on the device handles it,
     * [LaunchOutcome.Blocked] for the reason given on [launchApp].
     */
    fun openUrl(url: String): LaunchOutcome

    /**
     * Sends an SMS to [to] with [body]. Returns false on failure (requires
     * `SEND_SMS`).
     */
    fun sendSms(to: String, body: String): Boolean

    /**
     * Initiates a phone call to [number] (ACTION_CALL — requires `CALL_PHONE`).
     *
     * [LaunchOutcome.NoHandler] when the permission is missing or nothing can
     * dial, [LaunchOutcome.Blocked] for the reason given on [launchApp].
     */
    fun call(number: String): LaunchOutcome

    /**
     * Sets the clipboard primary clip to [text]. Returns false on failure.
     */
    fun setClipboard(text: String): Boolean

    /** Clears the clipboard primary clip. Returns false on failure. */
    fun clearClipboard(): Boolean
}

data class HttpRequest(
    val method: HttpMethod,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val body: String = "",
)

/**
 * One playback of one sound, for [SystemServices.playSound].
 *
 * - [sound]: which sound — a device default, or the one [uri] identifies.
 * - [uri]: the sound to play when [sound] is [SoundSource.CUSTOM].
 * - [stream]: whose volume the sound obeys.
 * - [waitForCompletion]: whether the caller waits for the sound to finish.
 * - [startMs]: where in the sound to start; 0 plays it from the beginning.
 * - [maxMs]: how long to play for at most; 0 plays to the end. Ringtones and
 *   alarms are written to keep going until something answers them, so the cap
 *   is what makes them usable as a short cue.
 */
data class SoundRequest(
    val sound: SoundSource,
    val uri: String = "",
    val stream: AudioStream = AudioStream.NOTIFICATION,
    val waitForCompletion: Boolean = false,
    val startMs: Int = 0,
    val maxMs: Int = 0,
)

data class HttpResponse(
    val statusCode: Int,
    val body: String,
    val headers: Map<String, String> = emptyMap(),
)

/**
 * Result of a volume change via [SystemServices.setVolume].
 *
 * - [stream]: which audio stream was adjusted.
 * - [mode]: the requested change.
 * - [volume]: resulting volume index.
 * - [maxVolume]: maximum index for the stream.
 * - [changed]: whether the call was accepted.
 */
data class VolumeResult(
    val stream: AudioStream,
    val mode: VolumeMode,
    val volume: Int,
    val maxVolume: Int,
    val changed: Boolean,
)

/**
 * Result of a Do-Not-Disturb toggle via [SystemServices.setDnd].
 *
 * - [enabled]: whether DND is now active.
 * - [level]: policy in effect ([DndLevel.ALL] when DND is off).
 * - [changed]: whether the call was accepted.
 */
data class DndResult(
    val enabled: Boolean,
    val level: DndLevel,
    val changed: Boolean,
)

/** Result of [SystemServices.setBluetooth]. */
data class BluetoothResult(
    val enabled: Boolean,
    val changed: Boolean,
)

/** Result of [SystemServices.setRingerMode]. */
data class RingerResult(
    val mode: RingerMode,
    val changed: Boolean,
)

/** Result of [SystemServices.setBrightness]. */
data class BrightnessResult(
    val value: Int,
    val auto: Boolean,
    val changed: Boolean,
)

/** Result of [SystemServices.setScreenTimeout]. */
data class ScreenTimeoutResult(
    val ms: Int,
    val changed: Boolean,
)

/** Result of [SystemServices.setAutoRotate]. */
data class AutoRotateResult(
    val enabled: Boolean,
    val changed: Boolean,
)

/** Result of [SystemServices.setTorch]. */
data class TorchResult(
    val enabled: Boolean,
    val changed: Boolean,
)

/**
 * What happened when a node asked to put *something else* on screen —
 * [SystemServices.launchApp], [SystemServices.openUrl], [SystemServices.call].
 *
 * A boolean cannot carry [Blocked], and [Blocked] is the one the user can
 * actually fix. From Android 10 an app with no visible window may not start an
 * Activity, and the system logs "Background activity launch blocked!" to Logcat
 * while returning **nothing** to the caller — so before this existed,
 * `startActivity` "succeeded", the node reported success, and the run log showed
 * a clean pass for a launch that never happened. Running a foreground service is
 * *not* an exemption from that rule; holding `SYSTEM_ALERT_WINDOW` is, which is
 * why the check behind this is the same one
 * [com.example.ottomatic.data.prompt.OverlayPrompts] already makes.
 *
 * The three failures are kept apart because they send the user to three
 * different places: install the app, install something that handles it, or grant
 * a permission.
 */
sealed interface LaunchOutcome {

    /** Handed to the platform, which accepted it. */
    data object Launched : LaunchOutcome

    /** No app with that package, or it has no launcher activity. */
    data object NoSuchApp : LaunchOutcome

    /** Nothing on the device handles it — no browser, no dialler, no permission to dial. */
    data object NoHandler : LaunchOutcome

    /** Android refused the start: Ottomatic is in the background and may not draw over other apps. */
    data object Blocked : LaunchOutcome
}
