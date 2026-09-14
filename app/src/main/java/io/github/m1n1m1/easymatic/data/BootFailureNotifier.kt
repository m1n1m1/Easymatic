package io.github.m1n1m1.easymatic.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import io.github.m1n1m1.easymatic.MainActivity
import io.github.m1n1m1.easymatic.R

/**
 * Posts the "macros couldn't resume after reboot" notification. Tapping it opens
 * [MainActivity], which surfaces the battery-optimisation prompt.
 *
 * Separate channel (DEFAULT importance) from the low-priority engine-running
 * notification in [io.github.m1n1m1.easymatic.engine.service.MacroEngineService],
 * because this is an actionable problem the user should actually see.
 *
 * Requires `POST_NOTIFICATIONS` (granted at runtime; persists across reboot).
 * If not granted, the notification silently does not show — in that case the
 * only fallback is the next-launch prompt, which does not need the permission.
 */
object BootFailureNotifier {

    private const val CHANNEL_ID = "easymatic.boot_failure"
    private const val NOTIFICATION_ID = 4243

    fun notify(context: Context) {
        val app = context.applicationContext
        ensureChannel(app)
        val tapIntent = Intent(app, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(
            app,
            0,
            tapIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = Notification.Builder(app, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_easymatic)
            .setContentTitle("Easymatic macros paused")
            .setContentText("Tap to resume background automation.")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, notification)
    }

    private fun ensureChannel(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Macro resume alerts",
                    NotificationManager.IMPORTANCE_DEFAULT,
                ),
            )
        }
    }
}
