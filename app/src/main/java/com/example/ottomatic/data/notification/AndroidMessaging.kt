package com.example.ottomatic.data.notification

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.core.app.RemoteInput
import com.example.ottomatic.core.service.MessageReply
import com.example.ottomatic.core.service.Messaging
import com.example.ottomatic.core.service.MessagingResult
import com.example.ottomatic.core.service.NotificationAct
import com.example.ottomatic.core.service.NotificationOp

/**
 * [Messaging] over the notification a messenger already posted.
 *
 * Sending is not an API call — there is no messenger API on this phone to call. It
 * is the app's *own* reply button, pressed programmatically: the `PendingIntent`
 * behind it is filled in with the same `RemoteInput` bundle a smartwatch or Android
 * Auto would send, and the messenger cannot tell the difference because there is no
 * difference. That is why one implementation covers every app, and why none of it
 * needs a permission beyond the notification access the listener already required.
 *
 * Nothing here throws: every path answers a [MessagingResult], because the commonest
 * failure by far is not a failure of the phone at all — the user opened the chat, the
 * notification went away, and with it the only handle that existed.
 */
class AndroidMessaging(private val context: Context) : Messaging {

    override fun reply(request: MessageReply): MessagingResult {
        val tracked = find(request.packageName, request.key)
        val action = tracked?.replyAction()
        return when {
            request.text.isBlank() -> MessagingResult(done = false, error = "There is no text to send")
            tracked == null -> gone(request.packageName)
            action == null -> MessagingResult(
                done = false,
                error = "${request.packageName} does not offer a reply button on this notification",
            )
            else -> fire(action, typed(action, request.text))
        }
    }

    /** The intent that carries [text] into [action]'s `RemoteInput`s. */
    private fun typed(action: ActiveNotifications.TrackedAction, text: String): Intent {
        val results = Bundle()
        action.remoteInputs.forEach { results.putCharSequence(it.resultKey, text) }
        return Intent().also { intent ->
            RemoteInput.addResultsToIntent(action.remoteInputs, intent, results)
            // Without this, a messenger that distinguishes dictated text from typed
            // text may treat the reply as a voice transcription and ask for
            // confirmation — which on an unattended macro means nothing is sent.
            RemoteInput.setResultsSource(intent, RemoteInput.SOURCE_FREE_FORM_INPUT)
        }
    }

    override fun act(request: NotificationAct): MessagingResult = when (request.op) {
        NotificationOp.DISMISS ->
            if (ActiveNotifications.dismiss(request.key)) {
                MessagingResult(done = true)
            } else {
                gone(request.packageName)
            }

        NotificationOp.MARK_READ -> {
            val tracked = find(request.packageName, request.key)
            val action = tracked?.markReadAction()
            when {
                tracked == null -> gone(request.packageName)
                action == null -> MessagingResult(
                    done = false,
                    error = "${request.packageName} does not offer a mark-as-read button on this notification",
                )
                else -> fire(action, null)
            }
        }

        NotificationOp.RUN_ACTION -> {
            val tracked = find(request.packageName, request.key)
            val action = tracked?.actionLabelled(request.label)
            when {
                tracked == null -> gone(request.packageName)
                request.label.isBlank() -> MessagingResult(done = false, error = "No action named")
                // Names what it looked for *and* what was there, because the label is
                // whatever the app printed in whatever language the phone is set to,
                // and "no such button" with the list beside it is the whole diagnosis.
                action == null -> MessagingResult(
                    done = false,
                    error = "No button called \"${request.label.trim()}\" — this notification offers " +
                        tracked.actions.joinToString { "\"${it.title}\"" }.ifBlank { "none" },
                )
                else -> fire(action, null)
            }
        }
    }

    /**
     * The tracked notification [key] names, or null when it is gone or belongs to a
     * different app than the reference claims.
     *
     * The package check is not paranoia: a notification key is reused by the platform
     * once its notification is cancelled, so a reference held across a
     * `action.wait_until` could name a live notification belonging to something else
     * entirely — and replying into it would send the message to the wrong app.
     */
    private fun find(packageName: String, key: String): ActiveNotifications.Tracked? =
        ActiveNotifications.find(key)?.takeIf { it.packageName == packageName }

    private fun fire(action: ActiveNotifications.TrackedAction, intent: Intent?): MessagingResult =
        runCatching {
            action.intent.send(context, 0, intent)
            MessagingResult(done = true)
        }.getOrElse { failure ->
            // A cancelled PendingIntent is the ordinary end of a conversation's life,
            // not a fault: the messenger revokes it the moment the notification goes.
            MessagingResult(done = false, error = failure.message ?: "The app refused the request")
        }

    private fun gone(packageName: String) = MessagingResult(
        done = false,
        error = "That conversation's notification is gone, so there is nothing left to answer — " +
            "$packageName revokes the handle once the chat has been read or dismissed",
    )
}
