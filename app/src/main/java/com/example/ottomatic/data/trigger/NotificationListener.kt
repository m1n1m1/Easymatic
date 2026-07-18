package com.example.ottomatic.data.trigger

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.example.ottomatic.core.trigger.TriggerBus
import com.example.ottomatic.core.trigger.TriggerEvent
import com.example.ottomatic.core.trigger.TriggerSource

/**
 * Listens for notifications posted by other apps and pushes a [TriggerEvent]
 * for each. Requires the user to grant notification access in Settings.
 *
 * Registered in the manifest with the BIND_NOTIFICATION_LISTENER_SERVICE
 * permission so only the system can bind to it.
 */
class NotificationListener : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val notification: Notification = sbn.notification ?: return
        val extras = notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        TriggerBus.emit(
            TriggerEvent(
                source = TriggerSource.NOTIFICATION,
                triggerNodeId = NotificationTriggerBridge.NODE_ID_SENTINEL,
                payload = mapOf(
                    "package" to sbn.packageName,
                    "title" to title,
                    "text" to text,
                    "timestamp" to System.currentTimeMillis().toString(),
                ),
            ),
        )
    }
}

object NotificationTriggerBridge {
    const val NODE_ID_SENTINEL = "*"
}
