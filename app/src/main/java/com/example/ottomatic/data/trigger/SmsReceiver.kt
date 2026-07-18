package com.example.ottomatic.data.trigger

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import com.example.ottomatic.core.trigger.TriggerBus
import com.example.ottomatic.core.trigger.TriggerEvent
import com.example.ottomatic.core.trigger.TriggerSource

/**
 * Receives SMS_DELIVER/SMS_RECEIVED broadcasts and pushes a [TriggerEvent]
 * onto the [TriggerBus]. Manifest-registered so it works from a killed app.
 *
 * Requires the RECEIVE_SMS permission.
 */
class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_DELIVER_ACTION) return
        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        val sender = messages.firstOrNull()?.displayOriginatingAddress.orEmpty()
        val body = messages.joinToString("") { it.displayMessageBody.orEmpty() }
        TriggerBus.emit(
            TriggerEvent(
                source = TriggerSource.SMS,
                triggerNodeId = NODE_ID_SENTINEL,
                payload = mapOf(
                    "sender" to sender,
                    "body" to body,
                    "timestamp" to System.currentTimeMillis().toString(),
                ),
            ),
        )
    }

    companion object {
        // System receivers don't know which workflow node they belong to.
        // The SmsTrigger fans this out to all sms trigger nodes by matching
        // on source + sender filter.
        const val NODE_ID_SENTINEL = "*"
    }
}
