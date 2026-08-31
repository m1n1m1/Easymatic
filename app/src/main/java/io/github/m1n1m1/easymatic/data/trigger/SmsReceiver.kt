package io.github.m1n1m1.easymatic.data.trigger

import io.github.m1n1m1.easymatic.core.model.NodeId
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import io.github.m1n1m1.easymatic.core.trigger.TriggerBus
import io.github.m1n1m1.easymatic.core.trigger.TriggerEvent
import io.github.m1n1m1.easymatic.core.trigger.TriggerSource

/**
 * Receives SMS_RECEIVED broadcasts and pushes a [TriggerEvent] onto the
 * [TriggerBus]. Manifest-registered so it works from a killed app.
 *
 * Requires the RECEIVE_SMS permission, and SMS_RECEIVED is exactly the broadcast
 * that permission grants. Its neighbour SMS_DELIVER is *not*: the platform sends
 * that one to the default SMS app and to nothing else, so listening for it made
 * this trigger unfireable while still asking for a Play-restricted permission.
 * See the receiver's manifest entry for why becoming the default SMS app is not
 * the alternative.
 */
class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        val sender = messages.firstOrNull()?.displayOriginatingAddress.orEmpty()
        val body = messages.joinToString("") { it.displayMessageBody.orEmpty() }
        TriggerBus.emitOrHoldBroadcast(
            TriggerEvent(
                source = TriggerSource.SMS,
                triggerNodeId = NodeId.BROADCAST,
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
    }
}
