package io.github.m1n1m1.easymatic.data.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import io.github.m1n1m1.easymatic.core.service.NotificationAnswer
import io.github.m1n1m1.easymatic.data.service.ForegroundGrant

/**
 * Where a tap, a button press or a swipe on one of *this app's* notifications lands.
 *
 * Not exported, and correctly so on `RunTilePinnedReceiver`'s reasoning: it is only
 * ever reached through a `PendingIntent` this app created, which the system sends with
 * this app's identity however far it travelled through the shade on the way. Nothing
 * else has any business delivering a button press for a macro.
 *
 * A receiver rather than an Activity, unlike `RunTriggerActivity`. A shortcut needs an
 * Activity because `ShortcutManagerCompat` will take nothing else; a notification takes
 * any of the three, and a receiver is the one that puts no window on screen and no task
 * in Recents for what is usually an invisible act. Android 12's ban on notification
 * trampolines is about a notification starting an Activity that starts *another*
 * Activity, which is exactly what this does not do: it completes a `Deferred` and
 * returns, and any app that then opens is opened by an `action.launch_app` the user
 * wired up.
 *
 * [ForegroundGrant] is stamped here rather than inside [AndroidNotifications], because
 * this is the moment the *user* acted — which is the thing the platform's start
 * allowance actually hangs on. Stamping at post time would open a window for a
 * notification nobody had touched.
 */
internal class NotificationResponseReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_RESPOND) return
        val token = intent.getLongExtra(EXTRA_TOKEN, 0)
        if (token == 0L) return
        val index = intent.getIntExtra(EXTRA_INDEX, DISMISSED)
        ForegroundGrant.stamp()
        // Swiped away delivers null rather than an answer: nobody decided anything, so
        // the waiting branch must not fire — see `Notifications.await`. Delivering it
        // *at all* is what releases the pending-wait slot instead of holding it until
        // the timeout for a notification that is no longer on screen.
        NotificationResponses.deliver(token, if (index == DISMISSED) null else answer(context, intent, index))
    }

    private fun answer(context: Context, intent: Intent, index: Int): NotificationAnswer {
        val tag = intent.getStringExtra(EXTRA_TAG).orEmpty()
        if (tag.isNotEmpty() && !intent.getBooleanExtra(EXTRA_ONGOING, false)) {
            AndroidNotifications.takeDown(context, tag)
        }
        return NotificationAnswer(
            label = intent.getStringExtra(EXTRA_LABEL).orEmpty(),
            index = index,
            reply = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_REPLY)?.toString().orEmpty(),
        )
    }

    internal companion object {

        const val ACTION_RESPOND = "io.github.m1n1m1.easymatic.action.NOTIFICATION_RESPONSE"

        const val EXTRA_TOKEN = "token"
        const val EXTRA_INDEX = "index"
        const val EXTRA_LABEL = "label"
        const val EXTRA_TAG = "tag"
        const val EXTRA_ONGOING = "ongoing"

        /** The `RemoteInput` result key. One per notification is plenty; there is one field. */
        const val KEY_REPLY = "reply"

        /**
         * [EXTRA_INDEX] on the `deleteIntent` — the notification went away untouched.
         *
         * A third value beside "a button" and
         * [io.github.m1n1m1.easymatic.core.service.NotificationAnswer.TAPPED] rather than a
         * separate action string, so a malformed intent missing the extra defaults to
         * the harmless reading: give up waiting, pulse nothing.
         */
        const val DISMISSED = -2
    }
}
