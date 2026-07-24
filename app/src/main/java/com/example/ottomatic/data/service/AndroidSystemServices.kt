package com.example.ottomatic.data.service

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
import android.media.AudioManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.telephony.SmsManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.ottomatic.core.service.AudioStream
import com.example.ottomatic.core.service.AutoRotateResult
import com.example.ottomatic.core.service.BluetoothResult
import com.example.ottomatic.core.service.BrightnessResult
import com.example.ottomatic.core.service.DndLevel
import com.example.ottomatic.core.service.DndResult
import com.example.ottomatic.core.service.HttpRequest
import com.example.ottomatic.core.service.HttpResponse
import com.example.ottomatic.core.service.RingerMode
import com.example.ottomatic.core.service.RingerResult
import com.example.ottomatic.core.service.ScreenTimeoutResult
import com.example.ottomatic.core.service.SystemServices
import com.example.ottomatic.core.service.TorchResult
import com.example.ottomatic.core.service.VolumeMode
import com.example.ottomatic.core.service.VolumeResult
import java.net.HttpURLConnection
import java.net.URL

/**
 * Android-backed implementation of [SystemServices]. Bridges the pure-Kotlin
 * action contracts to NotificationManager, WifiManager and HttpURLConnection.
 */
@Suppress("TooManyFunctions") // Implements every SystemServices facade method.
class AndroidSystemServices(private val context: Context) : SystemServices {

    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    init {
        ensureChannel()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Ottomatic",
                NotificationManager.IMPORTANCE_DEFAULT,
            )
            notificationManager.createNotificationChannel(channel)
        }
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
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return@runCatching null
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
        // Silent requires notification policy access on API 21+.
        if (ringerMode == AudioManager.RINGER_MODE_SILENT &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (pattern.isNotEmpty()) {
                val amplitudes = IntArray(pattern.size) { VibrationEffect.DEFAULT_AMPLITUDE }
                vibrator.vibrate(VibrationEffect.createWaveform(pattern.toLongArray(), amplitudes, -1))
            } else {
                vibrator.vibrate(VibrationEffect.createOneShot(durationMs.toLong(), VibrationEffect.DEFAULT_AMPLITUDE))
            }
        } else {
            @Suppress("DEPRECATION")
            if (pattern.isNotEmpty()) vibrator.vibrate(pattern.toLongArray(), -1)
            else vibrator.vibrate(durationMs.toLong())
        }
        true
    }.getOrDefault(false)

    override fun launchApp(packageName: String): Boolean = runCatching {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return@runCatching false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    }.getOrDefault(false)

    override fun openUrl(url: String): Boolean = runCatching {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        true
    }.getOrDefault(false)

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

    override fun call(number: String): Boolean = runCatching {
        val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$number")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        true
    }.getOrDefault(false)

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

    private fun canWriteSettings(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.System.canWrite(context)

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
    }
}
