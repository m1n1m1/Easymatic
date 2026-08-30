package io.github.m1n1m1.easymatic.data.notification

import android.app.PendingIntent
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput

/**
 * The conversations that can still be answered, and the messages already reported.
 *
 * A process-wide `object` rather than something `ServiceLocator` builds, for
 * `VariableStore`'s reason: the thing that fills it is a
 * [android.service.notification.NotificationListenerService], which the *system*
 * constructs and destroys, so it can never be handed a dependency. The engine reads
 * the same instance from the other side through
 * [io.github.m1n1m1.easymatic.core.service.Messaging].
 *
 * It holds the reply handles and the dedup state together because they share a
 * lifetime exactly: both are keyed by notification key, and both are dropped by the
 * same `onNotificationRemoved`.
 *
 * **Bounded**, and that is not tidiness — each entry holds live [PendingIntent]s
 * belonging to another app, and a phone left in a busy group chat would otherwise
 * accumulate them for the life of the process.
 */
object ActiveNotifications {

    private val lock = Any()

    /** Insertion-ordered, so the oldest conversation is the one evicted. */
    private val tracked = LinkedHashMap<String, Tracked>()

    private val dedup = MessageDedup()

    /**
     * How a notification is cancelled, registered by the listener while it is bound.
     *
     * A lambda rather than the service instance, so nothing here can outlive the
     * binding or keep a destroyed `Service` alive.
     */
    @Volatile
    private var cancel: ((String) -> Unit)? = null

    /** Called from `onListenerConnected`. */
    fun attach(cancel: (String) -> Unit) {
        this.cancel = cancel
    }

    /** Called from `onListenerDisconnected`, after which every action fails closed. */
    fun detach() {
        cancel = null
        synchronized(lock) { tracked.clear() }
    }

    /** Records the actions [key] currently offers, replacing whatever it offered before. */
    fun remember(key: String, packageName: String, actions: List<TrackedAction>) = synchronized(lock) {
        tracked.remove(key)
        tracked[key] = Tracked(packageName, actions)
        while (tracked.size > MAX_TRACKED) tracked.remove(tracked.keys.first())
    }

    /** Drops everything held for [key], because its notification is gone. */
    fun forget(key: String) {
        synchronized(lock) { tracked.remove(key) }
        dedup.forget(key)
    }

    /** What [key] can still be asked to do, or null once the notification is gone. */
    fun find(key: String): Tracked? = synchronized(lock) { tracked[key] }

    /** Whether the post of [key] showing [fingerprint] is a message that has not been reported. */
    fun isNewMessage(key: String, fingerprint: String): Boolean = dedup.isNew(key, fingerprint)

    /**
     * Dismisses [key]'s notification, answering whether there was a listener bound to
     * do it.
     */
    fun dismiss(key: String): Boolean {
        val cancel = cancel ?: return false
        cancel(key)
        forget(key)
        return true
    }

    /** One notification's actions, and the app that posted it. */
    class Tracked(val packageName: String, val actions: List<TrackedAction>) {

        /**
         * The action that sends a reply, or null when the app attached none.
         *
         * Preferring [NotificationCompat.Action.SEMANTIC_ACTION_REPLY] over "the
         * first one with a text input" matters because messengers attach more than
         * one: WhatsApp's notification carries both Reply and Mark-as-read, and Mark
         * as read has no input but some apps do attach data-only inputs to actions
         * that are not replies at all. Meaning first, shape as the fallback.
         */
        fun replyAction(): TrackedAction? =
            actions.firstOrNull { it.semanticAction == NotificationCompat.Action.SEMANTIC_ACTION_REPLY && it.canType }
                ?: actions.firstOrNull { it.canType }

        /** The action Android marks as marking the conversation read, or null. */
        fun markReadAction(): TrackedAction? =
            actions.firstOrNull { it.semanticAction == NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ }

        /**
         * The action whose button reads [label], matched loosely.
         *
         * Loosely because this is the escape hatch: the label is whatever the app
         * chose to print in whatever language the phone is set to, so an exact match
         * would be a field almost nobody could fill in correctly.
         */
        fun actionLabelled(label: String): TrackedAction? {
            val wanted = label.trim()
            if (wanted.isEmpty()) return null
            return actions.firstOrNull { it.title.equals(wanted, ignoreCase = true) }
                ?: actions.firstOrNull { it.title.contains(wanted, ignoreCase = true) }
        }
    }

    /**
     * One button on a notification, reduced to what firing it needs.
     *
     * Deliberately not the [NotificationCompat.Action] itself: that carries an icon
     * and extras, and fifty of them held for the life of the process is a size nobody
     * would notice going wrong.
     */
    class TrackedAction(
        val title: String,
        val semanticAction: Int,
        val intent: PendingIntent,
        val remoteInputs: Array<RemoteInput>,
    ) {
        /** Whether firing this action can carry typed text. */
        val canType: Boolean get() = remoteInputs.isNotEmpty()
    }

    /**
     * How many conversations stay answerable. Well past what any notification shade
     * holds, and small enough that the [PendingIntent]s are never a cost.
     */
    private const val MAX_TRACKED = 50
}
