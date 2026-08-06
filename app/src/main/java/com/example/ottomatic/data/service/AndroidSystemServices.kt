package com.example.ottomatic.data.service

import android.app.ActivityManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.bluetooth.BluetoothManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.telephony.SmsManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.example.ottomatic.core.service.AudioStream
import com.example.ottomatic.core.service.AutoRotateResult
import com.example.ottomatic.core.service.BluetoothResult
import com.example.ottomatic.core.service.BrightnessResult
import com.example.ottomatic.core.service.DndLevel
import com.example.ottomatic.core.service.DndResult
import com.example.ottomatic.core.service.HttpRequest
import com.example.ottomatic.core.service.HttpResponse
import com.example.ottomatic.core.service.LaunchOutcome
import com.example.ottomatic.core.service.RingerMode
import com.example.ottomatic.core.service.RingerResult
import com.example.ottomatic.core.service.ScreenTimeoutResult
import com.example.ottomatic.core.service.SoundRequest
import com.example.ottomatic.core.service.SoundSource
import com.example.ottomatic.core.service.SystemServices
import com.example.ottomatic.core.service.TorchResult
import com.example.ottomatic.core.service.VolumeMode
import com.example.ottomatic.core.service.VolumeResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.HttpURLConnection
import java.net.URL
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * Android-backed implementation of [SystemServices]. Bridges the pure-Kotlin
 * action contracts to NotificationManager, WifiManager and HttpURLConnection.
 */
@Suppress("TooManyFunctions") // Implements every SystemServices facade method.
class AndroidSystemServices(private val context: Context) : SystemServices {

    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    /**
     * Every sound currently making noise. A fire-and-forget player is
     * referenced by nothing else and would be collected mid-sound; a waited-on
     * one is here so that stopping it is possible at all.
     */
    private val playing: MutableSet<PlayingSound> = Collections.synchronizedSet(mutableSetOf())

    private val playingState = MutableStateFlow(false)

    override val soundPlaying: StateFlow<Boolean> = playingState.asStateFlow()

    /** Carries the play-time cap of a sound nobody is waiting for. */
    private val mainHandler = Handler(Looper.getMainLooper())

    init {
        ensureChannel()
    }

    private fun ensureChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Ottomatic",
            NotificationManager.IMPORTANCE_DEFAULT,
        )
        notificationManager.createNotificationChannel(channel)
    }

    override fun notify(title: String, text: String): Boolean = runCatching {
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .build()
        notificationManager.notify(System.currentTimeMillis().toInt(), notification)
        true
    }.getOrDefault(false)

    override fun setWifi(enabled: Boolean): Boolean? = runCatching {
        @Suppress("DEPRECATION")
        val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION")
        wifiManager.setWifiEnabled(enabled)
    }.getOrNull()

    override fun httpRequest(request: HttpRequest): HttpResponse = runCatching {
        val connection = URL(request.url).openConnection() as HttpURLConnection
        connection.requestMethod = request.method.name
        connection.connectTimeout = TIMEOUT_MS
        connection.readTimeout = TIMEOUT_MS
        request.headers.forEach { (key, value) -> connection.setRequestProperty(key, value) }
        if (request.method.sendsBody && request.body.isNotEmpty()) {
            connection.doOutput = true
            connection.outputStream.use { it.write(request.body.toByteArray()) }
        }
        val statusCode = connection.responseCode
        val body = (connection.errorStream ?: connection.inputStream)?.bufferedReader()?.use { it.readText() } ?: ""
        connection.disconnect()
        HttpResponse(statusCode, body)
    }.getOrElse { e ->
        HttpResponse(-1, e.message ?: "Request failed")
    }

    override fun setVolume(stream: AudioStream, mode: VolumeMode, value: Int): VolumeResult? = runCatching {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val streamType = audioStreamType(stream)
        val maxVolume = audioManager.getStreamMaxVolume(streamType)
        when (mode) {
            VolumeMode.UP -> audioManager.adjustStreamVolume(
                streamType, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI,
            )
            VolumeMode.DOWN -> audioManager.adjustStreamVolume(
                streamType, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI,
            )
            VolumeMode.SET -> {
                val clamped = value.coerceIn(0, maxVolume)
                audioManager.setStreamVolume(streamType, clamped, AudioManager.FLAG_SHOW_UI)
            }
            VolumeMode.MUTE -> audioManager.adjustStreamVolume(
                streamType, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI,
            )
            VolumeMode.UNMUTE -> audioManager.adjustStreamVolume(
                streamType, AudioManager.ADJUST_UNMUTE, AudioManager.FLAG_SHOW_UI,
            )
        }
        val current = audioManager.getStreamVolume(streamType)
        VolumeResult(stream, mode, current, maxVolume, changed = true)
    }.getOrNull()

    override fun setDnd(enabled: Boolean, level: DndLevel): DndResult? = runCatching {
        if (!notificationManager.isNotificationPolicyAccessGranted) return@runCatching null
        val filter = if (enabled) {
            when (level) {
                DndLevel.ALARMS -> NotificationManager.INTERRUPTION_FILTER_ALARMS
                DndLevel.SILENCE -> NotificationManager.INTERRUPTION_FILTER_NONE
                DndLevel.PRIORITY -> NotificationManager.INTERRUPTION_FILTER_PRIORITY
                DndLevel.ALL -> NotificationManager.INTERRUPTION_FILTER_ALL
            }
        } else {
            NotificationManager.INTERRUPTION_FILTER_ALL
        }
        notificationManager.setInterruptionFilter(filter)
        val active = filter != NotificationManager.INTERRUPTION_FILTER_ALL
        val effective = when (filter) {
            NotificationManager.INTERRUPTION_FILTER_PRIORITY -> DndLevel.PRIORITY
            NotificationManager.INTERRUPTION_FILTER_ALARMS -> DndLevel.ALARMS
            NotificationManager.INTERRUPTION_FILTER_NONE -> DndLevel.SILENCE
            else -> DndLevel.ALL
        }
        DndResult(enabled = active, level = effective, changed = true)
    }.getOrNull()

    override fun setBluetooth(enabled: Boolean): BluetoothResult? = runCatching {
        val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            ?: return@runCatching null
        val adapter = bm.adapter ?: return@runCatching null
        val perm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            android.Manifest.permission.BLUETOOTH_CONNECT
        } else {
            android.Manifest.permission.BLUETOOTH
        }
        if (ContextCompat.checkSelfPermission(context, perm) != PackageManager.PERMISSION_GRANTED) {
            return@runCatching null
        }
        // Deprecated since API 33 with no direct replacement: the sanctioned path is
        // an ACTION_REQUEST_ENABLE intent, which cannot run unattended as a workflow
        // action must. Still functional on the OEM builds that permit it.
        @Suppress("DEPRECATION")
        if (enabled) adapter.enable() else adapter.disable()
        BluetoothResult(enabled = enabled, changed = true)
    }.getOrNull()

    override fun setRingerMode(mode: RingerMode): RingerResult? = runCatching {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val ringerMode = when (mode) {
            RingerMode.SILENT -> AudioManager.RINGER_MODE_SILENT
            RingerMode.VIBRATE -> AudioManager.RINGER_MODE_VIBRATE
            RingerMode.NORMAL -> AudioManager.RINGER_MODE_NORMAL
        }
        // Silent requires notification policy access.
        if (ringerMode == AudioManager.RINGER_MODE_SILENT &&
            !notificationManager.isNotificationPolicyAccessGranted
        ) {
            return@runCatching null
        }
        audioManager.ringerMode = ringerMode
        val effective = when (audioManager.ringerMode) {
            AudioManager.RINGER_MODE_SILENT -> RingerMode.SILENT
            AudioManager.RINGER_MODE_VIBRATE -> RingerMode.VIBRATE
            else -> RingerMode.NORMAL
        }
        RingerResult(mode = effective, changed = true)
    }.getOrNull()

    override fun setBrightness(value: Int, auto: Boolean): BrightnessResult? = runCatching {
        if (!canWriteSettings()) return@runCatching null
        if (auto) {
            Settings.System.putInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC,
            )
        } else {
            Settings.System.putInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
            )
            val clamped = value.coerceIn(MIN_BRIGHTNESS, MAX_BRIGHTNESS)
            Settings.System.putInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS,
                clamped,
            )
        }
        val appliedAuto = Settings.System.getInt(
            context.contentResolver,
            Settings.System.SCREEN_BRIGHTNESS_MODE,
            Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
        ) == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
        val appliedValue = Settings.System.getInt(
            context.contentResolver,
            Settings.System.SCREEN_BRIGHTNESS,
            MIN_BRIGHTNESS,
        )
        BrightnessResult(value = appliedValue, auto = appliedAuto, changed = true)
    }.getOrNull()

    override fun setScreenTimeout(ms: Int): ScreenTimeoutResult? = runCatching {
        if (!canWriteSettings()) return@runCatching null
        val clamped = ms.coerceAtLeast(MIN_SCREEN_TIMEOUT_MS)
        Settings.System.putInt(
            context.contentResolver,
            Settings.System.SCREEN_OFF_TIMEOUT,
            clamped,
        )
        val applied = Settings.System.getInt(
            context.contentResolver,
            Settings.System.SCREEN_OFF_TIMEOUT,
            clamped,
        )
        ScreenTimeoutResult(ms = applied, changed = true)
    }.getOrNull()

    override fun setAutoRotate(enabled: Boolean): AutoRotateResult? = runCatching {
        if (!canWriteSettings()) return@runCatching null
        Settings.System.putInt(
            context.contentResolver,
            Settings.System.ACCELEROMETER_ROTATION,
            if (enabled) 1 else 0,
        )
        val applied = Settings.System.getInt(
            context.contentResolver,
            Settings.System.ACCELEROMETER_ROTATION,
            0,
        ) == 1
        AutoRotateResult(enabled = applied, changed = true)
    }.getOrNull()

    override fun setTorch(enabled: Boolean): TorchResult? = runCatching {
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
            cameraManager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: return@runCatching null
        cameraManager.setTorchMode(cameraId, enabled)
        TorchResult(enabled = enabled, changed = true)
    }.getOrNull()

    override fun vibrate(durationMs: Int, pattern: List<Long>): Boolean = runCatching {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        if (pattern.isNotEmpty()) {
            val amplitudes = IntArray(pattern.size) { VibrationEffect.DEFAULT_AMPLITUDE }
            vibrator.vibrate(VibrationEffect.createWaveform(pattern.toLongArray(), amplitudes, REPEAT_NEVER))
        } else {
            vibrator.vibrate(VibrationEffect.createOneShot(durationMs.toLong(), VibrationEffect.DEFAULT_AMPLITUDE))
        }
        true
    }.getOrDefault(false)

    override suspend fun playSound(request: SoundRequest): Boolean {
        val player = preparedPlayer(request) ?: return false
        return if (request.waitForCompletion) {
            awaitPlayback(player, request.maxMs)
        } else {
            playDetached(player, request.maxMs)
        }
    }

    /**
     * A player ready to start at [SoundRequest.startMs], or null when there is
     * no such sound, it cannot be opened (a chosen file since deleted, or one
     * whose access grant is gone), or the start offset is past its end.
     *
     * `prepare` reads the file, so it stays off the caller's dispatcher. Only
     * this setup is wrapped in runCatching: doing that around the *awaiting*
     * half would swallow the CancellationException of a disarmed macro and let
     * the executor carry on down the graph.
     */
    private suspend fun preparedPlayer(request: SoundRequest): MediaPlayer? {
        val source = soundUri(request.sound, request.uri) ?: return null
        val player = MediaPlayer()
        val ready = withContext(Dispatchers.IO) {
            runCatching {
                player.setAudioAttributes(audioAttributes(request.stream))
                player.setDataSource(context, source)
                player.prepare()
                seekToStart(player, request.startMs)
            }.getOrDefault(false)
        }
        if (!ready) player.release()
        return player.takeIf { ready }
    }

    /**
     * Moves [player] to [startMs], reporting whether anything is left to play.
     * A duration of 0 or less means the player does not know it, so the offset
     * is taken on trust rather than refused.
     */
    private fun seekToStart(player: MediaPlayer, startMs: Int): Boolean {
        // An empty range when there is no offset, so an unset start always plays.
        val playable = player.duration !in 1..startMs
        if (playable && startMs > 0) player.seekTo(startMs)
        return playable
    }

    override fun stopSounds(): Int {
        // Snapshot first: silencing a sound takes it out of the set.
        val sounds = synchronized(playing) { playing.toList() }
        sounds.forEach { it.silence() }
        return sounds.size
    }

    /**
     * Plays [player] to its end — or for [maxMs], when that is set — stopping
     * it if the caller is cancelled. Hitting the cap still counts as played.
     */
    private suspend fun awaitPlayback(player: MediaPlayer, maxMs: Int): Boolean = try {
        if (maxMs > 0) {
            withTimeoutOrNull(maxMs.toLong()) { playToEnd(player) } ?: true
        } else {
            playToEnd(player)
        }
    } finally {
        // Covers every exit: completion, error, the cap, a stop and cancellation.
        player.release()
    }

    private suspend fun playToEnd(player: MediaPlayer): Boolean = suspendCancellableCoroutine { continuation ->
        // Stopping resumes rather than cancels: a cancellation would travel out
        // of the action and abort the whole macro run, where stopping a sound
        // should only end the sound and let the workflow carry on.
        val sound = register {
            runCatching { if (player.isPlaying) player.stop() }
            if (continuation.isActive) continuation.resume(false)
        }
        continuation.invokeOnCancellation {
            sound.silence()
            runCatching { player.stop() }
        }
        player.setOnCompletionListener {
            sound.forget()
            if (continuation.isActive) continuation.resume(true)
        }
        // Returning true marks the error handled, which suppresses the
        // completion callback that would otherwise resume this twice.
        player.setOnErrorListener { _, _, _ ->
            sound.forget()
            if (continuation.isActive) continuation.resume(false)
            true
        }
        player.start()
    }

    /**
     * Starts [player] and returns immediately, stopping it after [maxMs] when
     * that is set. The player is held in [playing] until it ends: nothing else
     * references it, and a collected MediaPlayer stops mid-sound.
     */
    private fun playDetached(player: MediaPlayer, maxMs: Int): Boolean {
        val sound = register {
            runCatching { if (player.isPlaying) player.stop() }
            player.release()
        }
        return runCatching {
            player.setOnCompletionListener { sound.silence() }
            player.setOnErrorListener { _, _, _ ->
                sound.silence()
                true
            }
            player.start()
            // Nothing cancels this when the sound ends on its own; silencing an
            // already-silent sound is a no-op.
            if (maxMs > 0) mainHandler.postDelayed({ sound.silence() }, maxMs.toLong())
            true
        }.getOrElse {
            sound.silence()
            false
        }
    }

    private fun register(teardown: () -> Unit): PlayingSound {
        val sound = PlayingSound(teardown)
        playing += sound
        playingState.value = playing.isNotEmpty()
        return sound
    }

    /**
     * One sound making noise, and how to silence it. The teardown runs at most
     * once, so the end of a sound and a stop racing each other is harmless.
     */
    private inner class PlayingSound(private val teardown: () -> Unit) {

        private val done = AtomicBoolean(false)

        /** Stops the sound and tears it down, unless it is already over. */
        fun silence() {
            if (done.compareAndSet(false, true)) {
                drop()
                runCatching { teardown() }
            }
        }

        /** Drops the sound without tearing it down: the caller is doing that. */
        fun forget() {
            if (done.compareAndSet(false, true)) drop()
        }

        private fun drop() {
            playing -= this
            playingState.value = playing.isNotEmpty()
        }
    }

    /** Null when there is no such sound: no device default, or a blank uri. */
    private fun soundUri(sound: SoundSource, uri: String): Uri? = when (sound) {
        SoundSource.NOTIFICATION -> RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        SoundSource.RINGTONE -> RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        SoundSource.ALARM -> RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        SoundSource.CUSTOM -> uri.takeIf { it.isNotBlank() }?.toUri()
    }

    /**
     * The playback usage matching [stream]. `setAudioStreamType` is deprecated
     * since API 26, so the stream choice is expressed as attributes instead.
     */
    private fun audioAttributes(stream: AudioStream): AudioAttributes = AudioAttributes.Builder()
        .setUsage(
            when (stream) {
                AudioStream.MEDIA -> AudioAttributes.USAGE_MEDIA
                AudioStream.RING -> AudioAttributes.USAGE_NOTIFICATION_RINGTONE
                AudioStream.ALARM -> AudioAttributes.USAGE_ALARM
                AudioStream.NOTIFICATION -> AudioAttributes.USAGE_NOTIFICATION
                AudioStream.SYSTEM -> AudioAttributes.USAGE_ASSISTANCE_SONIFICATION
            },
        )
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    override fun launchApp(packageName: String): LaunchOutcome {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            ?: return LaunchOutcome.NoSuchApp
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return startActivityForNode(intent)
    }

    override fun openUrl(url: String): LaunchOutcome = startActivityForNode(
        Intent(Intent.ACTION_VIEW, url.toUri()).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) },
    )

    /**
     * Starts [intent] on behalf of a node, saying honestly what became of it.
     *
     * The [LaunchOutcome.Blocked] check has to happen *before* the call, because
     * the platform will not tell us afterwards: a background activity start that
     * is refused throws nothing and returns nothing, it is simply dropped with a
     * "Background activity launch blocked!" line in Logcat. Every node that puts
     * another app on screen goes through here so they cannot disagree about it.
     */
    private fun startActivityForNode(intent: Intent): LaunchOutcome {
        if (!canStartActivity()) return LaunchOutcome.Blocked
        return runCatching {
            context.startActivity(intent)
            LaunchOutcome.Launched
        }.getOrDefault(LaunchOutcome.NoHandler)
    }

    /**
     * Whether an Activity started from here will actually appear.
     *
     * The two documented exemptions this app can ever be in: holding the overlay
     * grant, and having a visible window. The engine's own foreground-service
     * notification does **not** satisfy either — `IMPORTANCE_FOREGROUND` (100) is
     * strictly above `IMPORTANCE_FOREGROUND_SERVICE` (125), and a foreground
     * service is not on the exemption list at all. That is the whole defect this
     * exists to report.
     *
     * The visible-window half is what keeps the editor's Run button working for
     * somebody who has not granted the overlay permission.
     *
     * This is a prediction, not a guarantee — the grant can be revoked between
     * here and the call, and the platform still reports that to nobody. Which is
     * why the message the node writes says Android *would not let* it rather than
     * claiming certainty.
     */
    private fun canStartActivity(): Boolean = Settings.canDrawOverlays(context) || isVisibleToUser()

    private fun isVisibleToUser(): Boolean {
        val state = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(state)
        return state.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }

    override fun sendSms(to: String, body: String): Boolean = runCatching {
        val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(SmsManager::class.java)
        } else {
            @Suppress("DEPRECATION")
            SmsManager.getDefault()
        }
        smsManager.sendMultipartTextMessage(to, null, smsManager.divideMessage(body), null, null)
        true
    }.getOrDefault(false)

    // A missing CALL_PHONE throws SecurityException, which lands on NoHandler —
    // the node already declares that permission, so its card covers that case.
    override fun call(number: String): LaunchOutcome = startActivityForNode(
        Intent(Intent.ACTION_CALL, "tel:$number".toUri()).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) },
    )

    override fun setClipboard(text: String): Boolean = runCatching {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Ottomatic", text))
        true
    }.getOrDefault(false)

    override fun clearClipboard(): Boolean = runCatching {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        // API 28+: clear by setting empty text. Newer API 33+ supports directly clearing,
        // but setting empty text works on all versions.
        clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
        true
    }.getOrDefault(false)

    private fun canWriteSettings(): Boolean = Settings.System.canWrite(context)

    private fun audioStreamType(stream: AudioStream): Int = when (stream) {
        AudioStream.MEDIA -> AudioManager.STREAM_MUSIC
        AudioStream.RING -> AudioManager.STREAM_RING
        AudioStream.ALARM -> AudioManager.STREAM_ALARM
        AudioStream.NOTIFICATION -> AudioManager.STREAM_NOTIFICATION
        AudioStream.SYSTEM -> AudioManager.STREAM_SYSTEM
    }

    companion object {
        private const val CHANNEL_ID = "ottomatic_default"
        private const val TIMEOUT_MS = 15_000
        private const val MIN_BRIGHTNESS = 0
        private const val MAX_BRIGHTNESS = 255
        private const val MIN_SCREEN_TIMEOUT_MS = 1_000

        /** `VibrationEffect` repeat index meaning "play the waveform once". */
        private const val REPEAT_NEVER = -1
    }
}
