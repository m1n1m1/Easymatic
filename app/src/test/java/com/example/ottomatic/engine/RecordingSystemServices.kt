package com.example.ottomatic.engine

import com.example.ottomatic.core.service.AudioStream
import com.example.ottomatic.core.service.AutoRotateResult
import com.example.ottomatic.core.service.BluetoothResult
import com.example.ottomatic.core.service.BrightnessResult
import com.example.ottomatic.core.service.DndLevel
import com.example.ottomatic.core.service.DndResult
import com.example.ottomatic.core.service.HttpRequest
import com.example.ottomatic.core.service.HttpResponse
import com.example.ottomatic.core.service.MacroControl
import com.example.ottomatic.core.service.RingerMode
import com.example.ottomatic.core.service.RingerResult
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
    val notifications = mutableListOf<Pair<String, String>>()
    val smsSent = mutableListOf<Pair<String, String>>()
    val calls = mutableListOf<String>()
    val launchedApps = mutableListOf<String>()
    val openedUrls = mutableListOf<String>()

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
    var ringerMode: RingerMode? = null
    var volume: Triple<AudioStream, VolumeMode, Int>? = null
    var dnd: Pair<Boolean, DndLevel>? = null

    override fun notify(title: String, text: String): Boolean {
        notifications += title to text
        return true
    }

    override fun setWifi(enabled: Boolean): Boolean? {
        wifiEnabled = enabled
        return enabled
    }

    override fun httpRequest(request: HttpRequest): HttpResponse = HttpResponse(HTTP_OK, "")

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

    override fun launchApp(packageName: String): Boolean {
        launchedApps += packageName
        return true
    }

    override fun openUrl(url: String): Boolean {
        openedUrls += url
        return true
    }

    override fun sendSms(to: String, body: String): Boolean {
        smsSent += to to body
        return true
    }

    override fun call(number: String): Boolean {
        calls += number
        return true
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
