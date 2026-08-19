package com.example.ottomatic.engine

import com.example.ottomatic.core.service.AudioStream
import com.example.ottomatic.core.service.AutoRotateResult
import com.example.ottomatic.core.service.BluetoothResult
import com.example.ottomatic.core.service.BrightnessResult
import com.example.ottomatic.core.service.DndLevel
import com.example.ottomatic.core.service.DndResult
import com.example.ottomatic.core.service.HttpRequest
import com.example.ottomatic.core.service.HttpResponse
import com.example.ottomatic.core.service.IntentRecipe
import com.example.ottomatic.core.service.LaunchOutcome
import com.example.ottomatic.core.service.MacroControl
import com.example.ottomatic.core.service.MessengerRecipe
import com.example.ottomatic.core.service.RingerMode
import com.example.ottomatic.core.service.RingerResult
import com.example.ottomatic.core.service.ScreenRotation
import com.example.ottomatic.core.service.ScreenRotationResult
import com.example.ottomatic.core.service.ScreenTimeoutResult
import com.example.ottomatic.core.service.SoundRequest
import com.example.ottomatic.core.service.SystemServices
import com.example.ottomatic.core.service.TorchResult
import com.example.ottomatic.core.service.VolumeMode
import com.example.ottomatic.core.service.VolumeResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Recording [SystemServices] fake shared by the engine tests: every call is
 * captured so a test can assert on what an action actually asked the platform to
 * do. Previously each test file carried its own near-identical copy.
 */
@Suppress("TooManyFunctions") // Mirrors the SystemServices facade.
class RecordingSystemServices : SystemServices {

    /**
     * The notification recorder that goes with this one.
     *
     * Posting is [com.example.ottomatic.core.service.Notifications]' job rather than
     * this facade's, but the tests that watch `action.notify` want one object to reach
     * for — so it is carried here and passed alongside, rather than every executor test
     * building and threading a second fake of its own.
     */
    val notifier = RecordingNotifications()

    val smsSent = mutableListOf<Pair<String, String>>()
    val calls = mutableListOf<String>()
    val launchedApps = mutableListOf<String>()
    val openedUrls = mutableListOf<String>()

    /** One entry per [openMessenger] call: the whole recipe, so a test can assert on the URI. */
    val openedMessengers = mutableListOf<MessengerRecipe>()

    /** One entry per [httpRequest] call: what actually reached the network. */
    val httpRequests = mutableListOf<HttpRequest>()

    /** One entry per clipboard call: the text set, or null for a clear. */
    val clipboard = mutableListOf<String?>()

    /** One entry per [playSound] call. */
    val soundsPlayed = mutableListOf<SoundRequest>()

    /** How many times [stopSounds] was asked to silence everything. */
    var stopSoundCalls = 0
        private set

    private val playingState = MutableStateFlow(false)

    override val soundPlaying: StateFlow<Boolean> = playingState.asStateFlow()

    var torchEnabled: Boolean? = null
    var bluetoothEnabled: Boolean? = null
    var wifiEnabled: Boolean? = null
    var autoRotateEnabled: Boolean? = null
    var screenRotation: ScreenRotation? = null

    /** True answers as a phone that has not granted `WRITE_SETTINGS`, i.e. one that refuses. */
    var screenRotationRefused = false
    var ringerMode: RingerMode? = null
    var volume: Triple<AudioStream, VolumeMode, Int>? = null
    var dnd: Pair<Boolean, DndLevel>? = null

    override fun setWifi(enabled: Boolean): Boolean? {
        wifiEnabled = enabled
        return enabled
    }

    override fun httpRequest(request: HttpRequest): HttpResponse {
        httpRequests += request
        return HttpResponse(HTTP_OK, "")
    }

    override fun setVolume(stream: AudioStream, mode: VolumeMode, value: Int): VolumeResult? {
        volume = Triple(stream, mode, value)
        return VolumeResult(stream, mode, value, MAX_VOLUME, changed = true)
    }

    override fun setDnd(enabled: Boolean, level: DndLevel): DndResult? {
        dnd = enabled to level
        return DndResult(enabled = enabled, level = if (enabled) level else DndLevel.ALL, changed = true)
    }

    override fun setBluetooth(enabled: Boolean): BluetoothResult? {
        bluetoothEnabled = enabled
        return BluetoothResult(enabled = enabled, changed = true)
    }

    override fun setRingerMode(mode: RingerMode): RingerResult? {
        ringerMode = mode
        return RingerResult(mode = mode, changed = true)
    }

    override fun setBrightness(value: Int, auto: Boolean): BrightnessResult? =
        BrightnessResult(value = value, auto = auto, changed = true)

    override fun setScreenTimeout(ms: Int): ScreenTimeoutResult? =
        ScreenTimeoutResult(ms = ms, changed = true)

    override fun setAutoRotate(enabled: Boolean): AutoRotateResult? {
        autoRotateEnabled = enabled
        return AutoRotateResult(enabled = enabled, changed = true)
    }

    override fun setScreenRotation(rotation: ScreenRotation): ScreenRotationResult? {
        if (screenRotationRefused) return null
        screenRotation = rotation
        autoRotateEnabled = false
        return ScreenRotationResult(rotation = rotation, changed = true)
    }

    override fun setTorch(enabled: Boolean): TorchResult? {
        torchEnabled = enabled
        return TorchResult(enabled = enabled, changed = true)
    }

    override fun vibrate(durationMs: Int, pattern: List<Long>): Boolean = true

    override suspend fun playSound(request: SoundRequest): Boolean {
        soundsPlayed += request
        playingState.value = true
        return true
    }

    override fun stopSounds(): Int {
        stopSoundCalls++
        val stopped = soundsPlayed.size
        playingState.value = false
        return stopped
    }

    /**
     * What the next [launchApp] / [openUrl] / [call] answers.
     *
     * Settable because [LaunchOutcome.Blocked] is the interesting case and is
     * unreachable otherwise — it is the platform refusing a background Activity
     * start, which no unit test can provoke for real.
     */
    var launchOutcome: LaunchOutcome = LaunchOutcome.Launched

    override fun launchApp(packageName: String): LaunchOutcome {
        launchedApps += packageName
        return launchOutcome
    }

    override fun openUrl(url: String): LaunchOutcome {
        openedUrls += url
        return launchOutcome
    }

    /** Every [IntentRecipe] handed over, in order, so a test can assert on the whole recipe. */
    val sentIntents = mutableListOf<IntentRecipe>()

    override fun sendIntent(recipe: IntentRecipe): LaunchOutcome {
        sentIntents += recipe
        return launchOutcome
    }

    override fun sendSms(to: String, body: String): Boolean {
        smsSent += to to body
        return true
    }

    /**
     * The country code the fake normaliser assumes, or null for a phone that cannot
     * work out where it is — which is the interesting case, and unreachable
     * otherwise.
     */
    var callingCode: String? = "43"

    /**
     * A deliberately crude stand-in for `PhoneNumberUtils`: enough to tell a
     * national number from an international one, and nothing like the real table.
     * What the node tests are about is which of the two branches it takes.
     */
    override fun toInternationalNumber(number: String): String? {
        val digits = number.filter { it.isDigit() }
        val code = callingCode
        return when {
            digits.isEmpty() -> null
            '+' in number -> "+$digits"
            digits.startsWith("00") -> "+${digits.removePrefix("00")}"
            code == null -> null
            digits.startsWith("0") -> "+$code${digits.drop(1)}"
            else -> "+$code$digits"
        }
    }

    override fun openMessenger(recipe: MessengerRecipe): LaunchOutcome {
        openedMessengers += recipe
        return launchOutcome
    }

    override fun call(number: String): LaunchOutcome {
        calls += number
        return launchOutcome
    }

    override fun setClipboard(text: String): Boolean {
        clipboard += text
        return true
    }

    override fun clearClipboard(): Boolean {
        clipboard += null
        return true
    }

    private companion object {
        const val HTTP_OK = 200
        const val MAX_VOLUME = 15
    }
}

/** Recording [MacroControl] fake shared by the engine tests. */
class RecordingMacroControl : MacroControl {
    val enabled = mutableListOf<String>()
    val disabled = mutableListOf<String>()

    override fun enable(macroId: String): Boolean {
        enabled += macroId
        return true
    }

    override fun disable(macroId: String): Boolean {
        disabled += macroId
        return true
    }
}
