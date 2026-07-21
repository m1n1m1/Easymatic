package com.example.ottomatic.data.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.Build
import androidx.core.app.NotificationCompat
import com.example.ottomatic.core.service.DndResult
import com.example.ottomatic.core.service.HttpRequest
import com.example.ottomatic.core.service.HttpResponse
import com.example.ottomatic.core.service.SystemServices
import com.example.ottomatic.core.service.VolumeResult
import java.net.HttpURLConnection
import java.net.URL

/**
 * Android-backed implementation of [SystemServices]. Bridges the pure-Kotlin
 * action contracts to NotificationManager, WifiManager and HttpURLConnection.
 */
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
        connection.requestMethod = request.method
        connection.connectTimeout = TIMEOUT_MS
        connection.readTimeout = TIMEOUT_MS
        request.headers.forEach { (key, value) -> connection.setRequestProperty(key, value) }
        if (request.method in setOf("POST", "PUT") && request.body.isNotEmpty()) {
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

    override fun setVolume(stream: String, mode: String, value: Int): VolumeResult? = runCatching {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val streamType = audioStreamType(stream) ?: return@runCatching null
        val maxVolume = audioManager.getStreamMaxVolume(streamType)
        val appliedMode = if (mode.isBlank()) "up" else mode
        when (appliedMode) {
            "up" -> audioManager.adjustStreamVolume(
                streamType, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI,
            )
            "down" -> audioManager.adjustStreamVolume(
                streamType, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI,
            )
            "set" -> {
                val clamped = value.coerceIn(0, maxVolume)
                audioManager.setStreamVolume(streamType, clamped, AudioManager.FLAG_SHOW_UI)
            }
            "mute" -> audioManager.adjustStreamVolume(
                streamType, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI,
            )
            "unmute" -> audioManager.adjustStreamVolume(
                streamType, AudioManager.ADJUST_UNMUTE, AudioManager.FLAG_SHOW_UI,
            )
            else -> return@runCatching null
        }
        val current = audioManager.getStreamVolume(streamType)
        VolumeResult(stream, appliedMode, current, maxVolume, changed = true)
    }.getOrNull()

    override fun setDnd(enabled: Boolean, level: String): DndResult? = runCatching {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return@runCatching null
        if (!notificationManager.isNotificationPolicyAccessGranted) return@runCatching null
        val filter = if (enabled) {
            when (level) {
                "alarms" -> NotificationManager.INTERRUPTION_FILTER_ALARMS
                "silence" -> NotificationManager.INTERRUPTION_FILTER_NONE
                else -> NotificationManager.INTERRUPTION_FILTER_PRIORITY
            }
        } else {
            NotificationManager.INTERRUPTION_FILTER_ALL
        }
        notificationManager.setInterruptionFilter(filter)
        val active = filter != NotificationManager.INTERRUPTION_FILTER_ALL
        val levelName = when (filter) {
            NotificationManager.INTERRUPTION_FILTER_PRIORITY -> "priority"
            NotificationManager.INTERRUPTION_FILTER_ALARMS -> "alarms"
            NotificationManager.INTERRUPTION_FILTER_NONE -> "silence"
            else -> "all"
        }
        DndResult(enabled = active, level = levelName, changed = true)
    }.getOrNull()

    private fun audioStreamType(stream: String): Int? = when (stream) {
        "media" -> AudioManager.STREAM_MUSIC
        "ring" -> AudioManager.STREAM_RING
        "alarm" -> AudioManager.STREAM_ALARM
        "notification" -> AudioManager.STREAM_NOTIFICATION
        "system" -> AudioManager.STREAM_SYSTEM
        else -> null
    }

    companion object {
        private const val CHANNEL_ID = "ottomatic_default"
        private const val TIMEOUT_MS = 15_000
    }
}
