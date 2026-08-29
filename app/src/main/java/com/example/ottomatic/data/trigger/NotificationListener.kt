package com.example.ottomatic.data.trigger

import com.example.ottomatic.core.model.NodeId
import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.example.ottomatic.core.trigger.TriggerBus
import com.example.ottomatic.core.trigger.TriggerEvent
import com.example.ottomatic.core.trigger.TriggerSource
import com.example.ottomatic.data.call.CallSessions
import com.example.ottomatic.data.call.CallTransition
import com.example.ottomatic.data.call.DefaultDialer
import com.example.ottomatic.data.call.NotificationCalls
import com.example.ottomatic.data.call.toPayload
import com.example.ottomatic.data.notification.ActiveNotifications
import com.example.ottomatic.data.notification.NotificationMessages
import com.example.ottomatic.data.notification.ParsedMessage
import com.example.ottomatic.domain.model.ConversationRef

/**
 * Listens for notifications posted by other apps and pushes a [TriggerEvent]
 * for each. Requires the user to grant notification access in Settings.
 *
 * Registered in the manifest with the BIND_NOTIFICATION_LISTENER_SERVICE
 * permission so only the system can bind to it.
 *
 * It feeds **two** triggers from one callback, and the split is the design rather
 * than an optimisation. `trigger.notification` gets every post, unchanged, because
 * that is what it has always meant and every workflow already written depends on it.
 * `trigger.message` gets only the posts that read as a message from a messenger —
 * which is a far smaller set, carries far more (who wrote it, which chat, whether
 * it can be answered), and is the one that dedups.
 */
class NotificationListener : NotificationListenerService() {

    /**
     * Registers how a notification is cancelled, which is the one thing
     * `action.notification_action`'s Dismiss cannot do for itself: only a bound
     * listener may cancel another app's notification, and only the system may bind
     * one.
     */
    override fun onListenerConnected() {
        ActiveNotifications.attach { key -> cancelNotification(key) }
    }

    /**
     * Drops every handle when the binding goes, which is what makes a revoked
     * permission report honestly. Left in place, the tracked replies would linger and
     * a macro would fail with the messenger's cancelled-intent message instead of
     * "notification access is not switched on".
     */
    override fun onListenerDisconnected() {
        ActiveNotifications.detach()
        // Their notifications can never be seen to disappear now, so nothing would ever
        // close them and `value.call_active` would answer true forever. The cellular
        // session is left alone: telephony is a separate grant and reports its own end.
        CallSessions.forgetAppCalls()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val notification: Notification = sbn.notification ?: return
        val extras = notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        TriggerBus.emit(
            TriggerEvent(
                source = TriggerSource.NOTIFICATION,
                triggerNodeId = NodeId.BROADCAST,
                payload = mapOf(
                    KEY_PACKAGE to sbn.packageName,
                    KEY_TITLE to title,
                    KEY_TEXT to text,
                    KEY_TIMESTAMP to System.currentTimeMillis().toString(),
                ),
            ),
        )
        emitMessage(sbn)
        emitCall(sbn)
    }

    /**
     * Forgets a conversation the moment its notification goes.
     *
     * Both halves matter. The reply handle is dead — the messenger revokes its
     * `PendingIntent` — so holding it would turn "the chat has been read" into an
     * opaque failure from another app. And the dedup memory has to go with it, or a
     * messenger that re-posts the same last message after a re-sync would be silently
     * suppressed as a duplicate of something the user has already dealt with.
     */
    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        ActiveNotifications.forget(sbn.key)
        emitCallEnded(sbn)
    }

    /**
     * Publishes [sbn] as a call, if it is one and if it changes what was already known.
     *
     * Runs beside [emitMessage] rather than instead of it, and the two never collide:
     * `NotificationMessages` refuses an ongoing notification and `NotificationCalls`
     * requires one. A call app that also messages produces one or the other per post,
     * never both.
     *
     * [CallSessions] answers null for a notification that tells it nothing new — a call
     * app re-posting its ongoing notification every second while the timer ticks is the
     * normal case, and firing a macro on each of those would be the same bug
     * `MessageDedup` exists to prevent.
     */
    private fun emitCall(sbn: StatusBarNotification) {
        val call = NotificationCalls.read(sbn, packageManager) ?: return
        val transition = CallSessions.onCallNotification(
            key = sbn.key,
            call = call,
            nowMs = System.currentTimeMillis(),
            dialerPackage = DefaultDialer.packageName(this),
        ) ?: return
        emitCall(transition)
    }

    /**
     * Publishes the end of a call whose notification has just gone.
     *
     * This is the whole of "the call ended" for every app that is not the cellular radio,
     * because no app broadcasts anything when a call finishes — the notification going
     * away is the only signal Android gives. [CallSessions] answers null for every
     * notification that was not a call it was following, which is nearly all of them, and
     * also for a cellular call telephony has already closed.
     */
    private fun emitCallEnded(sbn: StatusBarNotification) {
        val transition = CallSessions.onNotificationGone(sbn.key, System.currentTimeMillis()) ?: return
        emitCall(transition)
    }

    private fun emitCall(transition: CallTransition) {
        TriggerBus.emitOrHoldBroadcast(
            TriggerEvent(
                source = TriggerSource.CALL,
                triggerNodeId = NodeId.BROADCAST,
                payload = transition.toPayload(),
            ),
        )
    }

    /**
     * Publishes [sbn] as a message, if it is one and if it is not the same message
     * this chat's notification was already showing.
     *
     * [TriggerBus.emitOrHoldBroadcast] rather than [TriggerBus.emit], following
     * `SmsReceiver`: a message that arrives while the engine is still arming after a
     * reboot is parked rather than dropped, and a message is exactly the thing a
     * person would never guess had been missed.
     */
    private fun emitMessage(sbn: StatusBarNotification) {
        val message = NotificationMessages.readAndTrack(sbn, packageManager) ?: return
        if (!ActiveNotifications.isNewMessage(sbn.key, message.fingerprint)) return
        TriggerBus.emitOrHoldBroadcast(
            TriggerEvent(
                source = TriggerSource.MESSAGE,
                triggerNodeId = NodeId.BROADCAST,
                payload = payloadOf(message, ConversationRef.format(sbn.packageName, sbn.key)),
                // Deliberately *not* stamped with the message's own time. That time
                // travels in the payload, where it belongs, but this field is what
                // the bus expires a held event on — and a messenger back from being
                // offline posts an hour of history at once, every line of which
                // would look stale enough to drop the instant it arrived.
            ),
        )
    }

    private fun payloadOf(message: ParsedMessage, ref: String) = mapOf(
        KEY_PACKAGE to message.packageName,
        KEY_APP_NAME to message.appName,
        KEY_CONVERSATION to message.conversation,
        KEY_SENDER to message.sender,
        KEY_TEXT to message.text,
        KEY_IS_GROUP to message.isGroup.toString(),
        KEY_CAN_REPLY to message.canReply.toString(),
        KEY_CONVERSATION_ID to ref,
        KEY_TIMESTAMP to message.timestamp.toString(),
    )

    companion object {

        const val KEY_PACKAGE = "package"
        const val KEY_TITLE = "title"
        const val KEY_TEXT = "text"
        const val KEY_TIMESTAMP = "timestamp"
        const val KEY_APP_NAME = "appName"
        const val KEY_CONVERSATION = "conversation"
        const val KEY_SENDER = "sender"
        const val KEY_IS_GROUP = "isGroup"
        const val KEY_CAN_REPLY = "canReply"
        const val KEY_CONVERSATION_ID = "conversationId"
    }
}
