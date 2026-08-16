package com.example.ottomatic.data.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import com.example.ottomatic.R
import com.example.ottomatic.core.service.NotificationAnswer
import com.example.ottomatic.core.service.NotificationRequest
import com.example.ottomatic.core.service.Notifications
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Android [Notifications]: `NotificationCompat`, plus the `PendingIntent` plumbing
 * that turns a button press back into a resumed branch.
 *
 * ### One channel, on purpose
 *
 * Everything posted here lands on the same `ottomatic_default` channel at
 * `IMPORTANCE_DEFAULT`. Importance is channel-bound from Android 8, so offering the
 * node a "how loud" field would mean a channel per level and a Settings screen listing
 * five Ottomatic entries — and, worse, a channel whose importance the user then edits
 * silently overrides whatever the node says forever after. That is a feature worth
 * having deliberately rather than as a side effect of this one.
 *
 * ### One notification per tag
 *
 * `notify(tag, id, …)` with a fixed id: the **tag** is the identity, so posting twice
 * under one tag updates rather than stacking, which is what makes a progress bar and a
 * "remove it again" node possible at all. Before tags existed the id was
 * `System.currentTimeMillis().toInt()` and a notification became unreachable the
 * instant it was posted.
 *
 * ### Mutable, and only where it has to be
 *
 * Every `PendingIntent` here is `FLAG_MUTABLE`, because `RemoteInput` works by the
 * *system* writing the typed text into the intent before sending it — an immutable one
 * arrives with an empty reply and no error anywhere. They are safe to make mutable
 * because they name an explicit component in this app: a filled-in intent still cannot
 * be redirected anywhere it was not already going.
 */
class AndroidNotifications(context: Context) : Notifications {

    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    init {
        // Guarded rather than re-registered unconditionally: creating a channel that
        // exists is a no-op to the platform but would be a second place claiming to
        // decide its importance, and the user's edit to it is the one that wins.
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT),
            )
        }
    }

    override suspend fun post(request: NotificationRequest): Long? = runCatching {
        val token = NotificationResponses.nextToken()
        // Registered before the notification exists, so a tap in the instant between
        // posting and awaiting has somewhere to land. See NotificationResponses.
        if (request.answerable) NotificationResponses.register(token)
        val built = build(request, token)
        manager.notify(request.tag, NOTIFY_ID, built)
        token
    }.getOrElse { null }

    override suspend fun await(token: Long, timeoutMs: Long): NotificationAnswer? {
        val waiter = NotificationResponses.register(token)
        return try {
            if (timeoutMs <= 0) {
                waiter.await()
            } else {
                withTimeoutOrNull(timeoutMs) { waiter.await() }
            }
        } finally {
            // Covers the timeout and the cancellation path alike: a macro disabled
            // mid-wait must not leave an entry behind for a button nobody will press.
            NotificationResponses.forget(token)
        }
    }

    override fun cancel(tag: String): Boolean {
        if (tag.isBlank()) return false
        val showing = isShowing(tag)
        manager.cancel(tag, NOTIFY_ID)
        return showing
    }

    /**
     * Whether something of ours is on screen under [tag].
     *
     * `getActiveNotifications` is this app's own list and needs no notification access
     * — that grant is for reading *other* apps'. It is worth the call because
     * `cancel` reports nothing at all, and "there was nothing to remove" is the answer
     * `action.notify_cancel` puts on its receipt.
     *
     * A phone that refuses the query is reported as "nothing was showing" rather than
     * as an error: the notification is gone either way, which is what was asked for.
     */
    private fun isShowing(tag: String): Boolean = runCatching {
        manager.activeNotifications.any { it.tag == tag && it.id == NOTIFY_ID }
    }.getOrDefault(false)

    private fun build(request: NotificationRequest, token: Long): android.app.Notification {
        val builder = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_macro_bolt)
            .setContentTitle(request.title)
            .setContentText(request.text)
            .setAutoCancel(!request.ongoing)
            .setOngoing(request.ongoing)
            // The expanded form. `setContentText` alone truncates to one line, which
            // for a macro reporting what it did is usually the half that matters.
            .setStyle(NotificationCompat.BigTextStyle().bigText(request.text))
        if (request.accentArgb != 0L) builder.setColor(request.accentArgb.toInt())
        if (request.hideAfterMs > 0) builder.setTimeoutAfter(request.hideAfterMs)
        if (request.progress != NotificationRequest.NO_PROGRESS) {
            builder.setProgress(PROGRESS_MAX, request.progress.coerceIn(0, PROGRESS_MAX), request.progress < 0)
        }
        if (request.answerable) intents(builder, request, token)
        return builder.build()
    }

    private fun intents(builder: NotificationCompat.Builder, request: NotificationRequest, token: Long) {
        builder.setContentIntent(responseIntent(request, token, NotificationAnswer.TAPPED, label = ""))
        builder.setDeleteIntent(
            responseIntent(request, token, NotificationResponseReceiver.DISMISSED, label = ""),
        )
        request.buttons.forEachIndexed { index, label ->
            val action = NotificationCompat.Action.Builder(
                /* icon = */ 0,
                label,
                responseIntent(request, token, index, label),
            )
            if (index == request.replyIndex) {
                action.addRemoteInput(
                    RemoteInput.Builder(NotificationResponseReceiver.KEY_REPLY)
                        .setLabel(request.replyHint.ifBlank { label })
                        .build(),
                )
            }
            builder.addAction(action.build())
        }
    }

    private fun responseIntent(
        request: NotificationRequest,
        token: Long,
        index: Int,
        label: String,
    ): PendingIntent {
        val intent = Intent(appContext, NotificationResponseReceiver::class.java).apply {
            action = NotificationResponseReceiver.ACTION_RESPOND
            putExtra(NotificationResponseReceiver.EXTRA_TOKEN, token)
            putExtra(NotificationResponseReceiver.EXTRA_INDEX, index)
            putExtra(NotificationResponseReceiver.EXTRA_LABEL, label)
            putExtra(NotificationResponseReceiver.EXTRA_TAG, request.tag)
            putExtra(NotificationResponseReceiver.EXTRA_ONGOING, request.ongoing)
        }
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) flags = flags or PendingIntent.FLAG_MUTABLE
        return PendingIntent.getBroadcast(appContext, NotificationResponses.nextRequestCode(), intent, flags)
    }

    internal companion object {

        /**
         * The one channel, keeping the id `AndroidSystemServices` used so a phone that
         * has already been told "allow Ottomatic notifications" is not asked again.
         */
        private const val CHANNEL_ID = "ottomatic_default"
        private const val CHANNEL_NAME = "Ottomatic"

        /**
         * The id every notification of ours shares, because the **tag** is the identity.
         *
         * `notify(tag, id, …)` keys on the pair, so one fixed id plus a distinct tag per
         * notification gives exactly the "same tag replaces, different tag coexists"
         * behaviour the node promises — with no id allocation to persist anywhere.
         */
        private const val NOTIFY_ID = 7301

        private const val PROGRESS_MAX = 100

        /**
         * Takes down the notification tagged [tag], from the receiver, which has a
         * `Context` but no [AndroidNotifications].
         */
        fun takeDown(context: Context, tag: String) {
            val manager = context.applicationContext
                .getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.cancel(tag, NOTIFY_ID)
        }
    }
}
