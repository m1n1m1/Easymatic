package io.github.m1n1m1.easymatic.core.service

import kotlinx.coroutines.flow.StateFlow

/**
 * Android-backed system operations exposed to actions without pulling
 * Android types into `engine/`. Implemented by [AndroidSystemServices] in `data/`.
 */
@Suppress("TooManyFunctions") // Facade over many independent Android subsystems.
interface SystemServices {

    // Posting a notification used to live here, and moved to [Notifications] when it
    // grew buttons and a reply field: this facade is write-only — every member changes
    // something and none of them waits for a reply — and waiting is most of what that
    // node now does. See [Prompts] for the same boundary drawn for the same reason.

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
     * Turns the screen to [rotation] and holds it there.
     *
     * Auto-rotation is switched **off** as part of the same call, because the
     * chosen rotation is only read while it is: without that the write succeeds,
     * the screen never moves, and nothing anywhere says why. Handing control back
     * is `action.auto_rotate` turning it on again.
     *
     * Returns null on failure (requires `WRITE_SETTINGS`).
     */
    fun setScreenRotation(rotation: ScreenRotation): ScreenRotationResult?

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
     * activity; [LaunchOutcome.Blocked] when Easymatic has no visible window and
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
     * Sends the intent [recipe] describes, as an Activity start or a broadcast.
     *
     * One member rather than two, on [openMessenger]'s reasoning: what can be *wrong* here is
     * the `Intent`'s construction — a data URI and a MIME type clobber one another, a
     * `content://` extra needs a grant conveyed with the launch, an extra that is a `String`
     * where an `int` was wanted is read as absent — and every bit of that is identical for both
     * targets. Only the last line differs. Two members would be four chances for the two paths
     * to drift.
     *
     * The recipe is built in `domain` by [io.github.m1n1m1.easymatic.domain.model.IntentSpec] where
     * it can be tested; this is the part that needs a platform.
     *
     * [LaunchOutcome.Blocked] is reachable only for [IntentTarget.ACTIVITY], for the reason
     * given on [launchApp]. [LaunchOutcome.NoReceiver] and [LaunchOutcome.Refused] are
     * reachable only for [IntentTarget.BROADCAST]. Per-target reachability rather than a second
     * outcome type, so `reportLaunch` stays the one place a failed launch is named.
     */
    fun sendIntent(recipe: IntentRecipe): LaunchOutcome

    /**
     * Sends an SMS to [to] with [body]. Returns false on failure (requires
     * `SEND_SMS`).
     */
    fun sendSms(to: String, body: String): Boolean

    /**
     * [number] in international (E.164) form — `+4915112345678` — or null when it
     * cannot be read as a number at all.
     *
     * The thing only the platform can supply: turning a **national** number into an
     * international one needs to know which country "national" means, and the phone
     * knows (its SIM) where neither `domain` nor the user's macro does.
     *
     * It is a platform call rather than a rule in `domain` because the rule is not
     * "swap the leading 0 for the country code". That is true across most of Europe
     * and false in Italy, where the 0 is part of the number, and meaningless in the
     * NANP, which has no trunk prefix at all. Android's `PhoneNumberUtils` carries
     * libphonenumber's table for every country; a hand-written version of it is the
     * kind of near-miss that sends a message to a stranger.
     *
     * Null rather than a best effort, so the caller can say the number could not be
     * normalised instead of quietly addressing the wrong person.
     */
    fun toInternationalNumber(number: String): String?

    /**
     * Opens a messenger with a message ready to send, from the recipe
     * [io.github.m1n1m1.easymatic.domain.model.MessengerLink] produced.
     *
     * A member of its own rather than a call to [openUrl], because only one of the
     * three apps is addressed by a URL: Signal takes an `smsto:` intent with the text
     * in an extra. The recipe is built in `domain` where it can be tested, and this
     * is the two lines of that which need a platform.
     *
     * [LaunchOutcome.NoSuchApp] when that messenger is not installed,
     * [LaunchOutcome.Blocked] for the reason given on [launchApp].
     */
    fun openMessenger(recipe: MessengerRecipe): LaunchOutcome

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

/** Result of [SystemServices.setScreenRotation]. */
data class ScreenRotationResult(
    val rotation: ScreenRotation,
    val changed: Boolean,
)

/**
 * One way of opening a messenger, as [io.github.m1n1m1.easymatic.domain.model.MessengerLink]
 * worked it out.
 *
 * It lives in `core` rather than beside the function that builds it because
 * [SystemServices] is the consumer and `core` may not import `domain` — the same
 * direction [MailSend] runs in, built by a node and carried inwards.
 *
 * [body] is carried apart from [uri] because `smsto:` puts the message in an intent
 * extra rather than in the URI: the one place the messengers genuinely disagree about
 * shape rather than about text.
 */
data class MessengerRecipe(
    val action: MessengerIntent,
    val uri: String,
    val packageName: String,
    val body: String = "",
    /**
     * Whether a recipient was named that this app cannot honour — true only for
     * Telegram, which can pre-fill the text or preselect the chat but never both.
     * The node logs it, because "it ignored who I addressed it to" is otherwise
     * something the user finds out by sending to the wrong person.
     */
    val losesRecipient: Boolean = false,
)

/** Which Android intent action a [MessengerRecipe] needs, named so `domain` imports no platform types. */
enum class MessengerIntent { VIEW, SENDTO }

/**
 * One intent to send, as [io.github.m1n1m1.easymatic.domain.model.IntentSpec] read it out of a
 * node's config.
 *
 * Lives here rather than beside the function that builds it for [MessengerRecipe]'s reason, and
 * it is the same journey one level more general: a recipe built and tested in `domain`, turned
 * into a real `Intent` in `data/`, so nothing outside `data/` imports a platform type.
 *
 * [data] and [mimeType] are carried **apart** even though `Intent` has a single setter for the
 * pair, because that is exactly where the platform's footgun is: `setData` clears the type and
 * `setType` clears the data. Keeping them separate here is what lets `AndroidSystemServices`
 * make the one unconditional `setDataAndType` call that cannot get it wrong.
 */
// One property per independent part of an Intent, all but the first two defaulted — the
// reasoning `@IntentChoice` records for its own parameter list, and the same Intent.
@Suppress("LongParameterList")
data class IntentRecipe(
    val target: IntentTarget,
    val action: String,
    val packageName: String = "",
    val data: String = "",
    val mimeType: String = "",
    val category: String = "",
    val extras: List<IntentExtra> = emptyList(),
)

/**
 * Which platform mechanism an [IntentRecipe] goes through, named here so `domain` imports no
 * platform types.
 *
 * There is deliberately no `SERVICE`. Since Android O a background app may not `startService` at
 * all, and the engine is a background service, so that is the *common* path rather than an edge
 * case; `startForegroundService` is reachable but makes a contract on the **callee** — the target
 * must call `startForeground()` within five seconds or the platform kills it, which we cannot
 * know of a foreign service. A node whose failure mode is crashing somebody else's app is not one
 * worth having. The named casualty is Termux's `com.termux.RUN_COMMAND`.
 *
 * An enum rather than a boolean so that adding it later, if the platform ever makes it safe, is
 * one member and one `when` branch instead of a redesign.
 */
enum class IntentTarget { ACTIVITY, BROADCAST }

/** One extra on an [IntentRecipe]: a key, and a value carrying the type it must be put on with. */
data class IntentExtra(val key: String, val value: IntentValue)

/**
 * The typed value of an [IntentExtra].
 *
 * **Sealed on purpose**, and the reason is on the far side: it makes the `putExtra` dispatch in
 * `AndroidSystemServices` a total `when` the compiler checks, rather than a chain of casts that
 * reports a wrong guess by not putting the extra on at all. The members are the `putExtra`
 * overloads that matter and no others — a closed set mirroring a closed set.
 *
 * [UriRef] carries a `String` rather than a `Uri` because `core` may not know about Android;
 * [MessengerRecipe.uri] already makes that trade for the same reason. It exists because
 * `Intent.EXTRA_STREAM` is read with `getParcelableExtra`, so a `String` there is read as
 * **absent** and the receiving app shows an empty share sheet — the clearest single case for
 * typing extras at all.
 */
sealed interface IntentValue {
    data class Text(val value: String) : IntentValue
    data class Texts(val values: List<String>) : IntentValue
    data class Int32(val value: Int) : IntentValue
    data class Int64(val value: Long) : IntentValue
    data class Float32(val value: Float) : IntentValue
    data class Float64(val value: Double) : IntentValue
    data class Flag(val value: Boolean) : IntentValue
    data class UriRef(val value: String) : IntentValue
}

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
 * [io.github.m1n1m1.easymatic.data.prompt.OverlayPrompts] already makes.
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

    /** Android refused the start: Easymatic is in the background and may not draw over other apps. */
    data object Blocked : LaunchOutcome

    /**
     * A broadcast that was sent, and that nothing on this phone appears to be listening for.
     *
     * The fourth place to send the user, and it is none of the other three: the recipient is very
     * likely installed and simply spells its action differently, so "install something that
     * handles it" would send somebody to the Play Store for an app already on the phone.
     *
     * **It is a suspicion, not a fact, and the broadcast is sent anyway.** The platform reports
     * nothing whatsoever about an undelivered broadcast — `sendBroadcast` returns `void` and
     * succeeds whether or not anybody is listening — so the only signal available is asked for
     * *beforehand*, from `queryBroadcastReceivers`, which cannot see receivers an app registered
     * in code while running and *can* see a manifest receiver that Android 8's implicit-broadcast
     * rule will nonetheless not deliver to. It errs in both directions.
     *
     * So this is the one member reported at WARN rather than ERROR, and the one for which
     * `reportLaunch` answers **true**: the thing was done, and may have reached nobody.
     */
    data object NoReceiver : LaunchOutcome

    /**
     * Android refused to send it at all: the action is a **protected broadcast**, which only the
     * system may send — `BOOT_COMPLETED`, `ACTION_SHUTDOWN` and the rest of a device-specific
     * list. `sendBroadcast` answers that with a `SecurityException`.
     *
     * Its own member rather than [Blocked], on [Blocked]'s own stated rule: they send the user to
     * two different places, and there is no grant that fixes this one. Naming "Display over other
     * apps" here would point somebody at a Settings switch that cannot help. It is also the
     * commonest single mistake with an action string copied off a forum, which is what makes it
     * worth a member of its own.
     */
    data object Refused : LaunchOutcome
}
