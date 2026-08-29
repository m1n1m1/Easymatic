package com.example.ottomatic.data.notification

import android.app.Notification
import android.content.pm.PackageManager
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat

/**
 * One message, as read out of the notification a messenger posted.
 *
 * The platform half of the messenger integration, kept apart from
 * [MessageDedup] on the split that file explains: the decisions worth testing live
 * there, and everything here needs a device.
 */
data class ParsedMessage(
    val packageName: String,
    val appName: String,
    val conversation: String,
    val sender: String,
    val text: String,
    val isGroup: Boolean,
    val canReply: Boolean,
    val timestamp: Long,
) {
    /**
     * What makes this post different from the last one for the same chat.
     *
     * The message's own time plus its text, and both halves earn their place: a
     * messenger re-posts the same notification when anything about it changes, so
     * the time alone would let an edited message through unnoticed and the text
     * alone would suppress "ok" sent twice in a row.
     */
    val fingerprint: String get() = "$timestamp|$sender|$text"
}

/**
 * Reads a posted notification as a message, or answers null when it is not one.
 *
 * There is no "is this a messenger?" list anywhere here, and there deliberately
 * never will be — a hard-coded set of packages is a integration that breaks the day
 * somebody installs the fifth messenger. What identifies a message is the *shape* of
 * the notification, and two shapes qualify:
 *
 * - it carries a **`MessagingStyle`**, which is the API Android added for exactly
 *   this and which WhatsApp, Signal, Telegram and Messenger all use; or
 * - it carries a **reply action**, which is the population this integration can act
 *   on anyway.
 *
 * Everything else is refused, and that strictness is the point: a notification with
 * a title and a body is not evidence of a message, or `trigger.message` would fire
 * on a promotional email and a delivery update.
 */
object NotificationMessages {

    /**
     * [sbn] as a message, or null — **and**, when it is one, records its buttons in
     * [ActiveNotifications] so the conversation can be answered.
     *
     * The two happen together on purpose rather than being split for tidiness: the
     * actions have to be read to decide whether this is a message at all, and
     * reading them twice is how the reply handle and the reported message end up
     * describing different posts.
     *
     * Two kinds of notification are dropped before anything is read, and both are
     * things a messenger genuinely posts:
     *
     * - a **group summary**, the "3 messages from 2 chats" roll-up that sits above
     *   the individual ones. It carries no message of its own and would fire the
     *   macro a second time for something already reported.
     * - an **ongoing** notification, which for a messenger is a call in progress or
     *   a media upload — a status, not an arrival.
     */
    fun readAndTrack(sbn: StatusBarNotification, packageManager: PackageManager): ParsedMessage? {
        val notification = sbn.notification ?: return null
        val ignorable = notification.flags and Notification.FLAG_GROUP_SUMMARY != 0 || sbn.isOngoing
        return if (ignorable) null else messageOf(sbn, notification, packageManager)
    }

    /** [readAndTrack] once the notification is known to be worth reading. */
    private fun messageOf(
        sbn: StatusBarNotification,
        notification: Notification,
        packageManager: PackageManager,
    ): ParsedMessage? {
        val actions = trackedActions(notification)
        val style = runCatching {
            NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification)
        }.getOrNull()
        val canReply = actions.any { it.canType }
        if (style == null && !canReply) return null

        ActiveNotifications.remember(sbn.key, sbn.packageName, actions)

        val extras = notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val latest = style?.messages?.lastOrNull()
        val conversationTitle = style?.conversationTitle?.toString()

        return ParsedMessage(
            packageName = sbn.packageName,
            appName = appNameOf(sbn.packageName, packageManager),
            conversation = conversationTitle?.takeIf { it.isNotBlank() } ?: title,
            // In a group the style names the person, which is the case this field
            // exists for; in a one-to-one chat it often names nobody, because the
            // conversation *is* the sender — and there the title is the answer.
            sender = latest?.person?.name?.toString()?.takeIf { it.isNotBlank() } ?: title,
            text = latest?.text?.toString()
                ?: extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty(),
            isGroup = style?.isGroupConversation == true,
            canReply = canReply,
            // A `MessagingStyle` message carries the time it was *written*, which is
            // not the time this notification was posted — a messenger back from
            // offline posts several minutes of history at once.
            timestamp = latest?.timestamp?.takeIf { it > 0 } ?: sbn.postTime,
        )
    }

    /**
     * The notification's buttons, reduced to what firing one needs.
     *
     * Read through [NotificationCompat.getAction] rather than off
     * `notification.actions`, because the platform class exposes no
     * `semanticAction` below API 28 and the compat wrapper reads it out of the
     * extras bundle on every version — which is what lets Reply and Mark-as-read be
     * told apart by meaning instead of by their English labels.
     */
    private fun trackedActions(notification: Notification): List<ActiveNotifications.TrackedAction> =
        (0 until NotificationCompat.getActionCount(notification)).mapNotNull { index ->
            val action = NotificationCompat.getAction(notification, index) ?: return@mapNotNull null
            val intent = action.actionIntent ?: return@mapNotNull null
            ActiveNotifications.TrackedAction(
                title = action.title?.toString().orEmpty(),
                semanticAction = action.semanticAction,
                intent = intent,
                // `getRemoteInputs` is the free-form half; `getDataOnlyRemoteInputs`
                // takes images and attachments and cannot carry typed text, so an
                // action offering only those is not a reply.
                remoteInputs = action.remoteInputs ?: emptyArray(),
            )
        }

    /**
     * The app's own name for itself, so a notification reads as "WhatsApp" rather
     * than `com.whatsapp` — the package is still carried separately, because that is
     * what a filter compares.
     *
     * Internal rather than private because `NotificationCalls` asks the same question of
     * the same stream, and two readings of "what is this app called?" would be two
     * places for it to come out differently.
     */
    internal fun appNameOf(packageName: String, packageManager: PackageManager): String = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString()
    }.getOrDefault(packageName)
}
