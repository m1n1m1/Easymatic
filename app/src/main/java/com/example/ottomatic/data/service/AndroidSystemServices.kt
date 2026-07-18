package com.example.ottomatic.data.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import androidx.core.app.NotificationCompat
import com.example.ottomatic.core.service.HttpRequest
import com.example.ottomatic.core.service.HttpResponse
import com.example.ottomatic.core.service.SystemServices
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

    companion object {
        private const val CHANNEL_ID = "ottomatic_default"
        private const val TIMEOUT_MS = 15_000
    }
}
