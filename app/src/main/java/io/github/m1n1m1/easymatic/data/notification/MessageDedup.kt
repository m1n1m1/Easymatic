package io.github.m1n1m1.easymatic.data.notification

/**
 * Decides which notification posts are genuinely a *new message*.
 *
 * A pure class with no Android types in sight, the same split
 * [io.github.m1n1m1.easymatic.data.mail.MailDedup] and `OrientationDetector.orientationOf`
 * make and for the same reason: this is the part that has to be tested, and a
 * `StatusBarNotification` does not exist on the JVM. It is also the part where being
 * wrong is worst — every mistake here is a macro that runs when it should not have.
 *
 * The problem it exists for is specific to messengers and invisible until you watch
 * one: a messenger does not post a notification per message, it **updates one
 * notification per chat**, re-posting it with the whole recent conversation every
 * time anything about it changes. `onNotificationPosted` therefore fires again for a
 * chat when the app merely refreshes it — a sync, an avatar arriving, the badge
 * count moving — and a naive trigger would re-fire the macro with the *same* message
 * each time.
 *
 * So the question is never "have I seen this notification?" but "have I seen this
 * notification **showing this message**?", and the answer is kept per key: a
 * fingerprint of the latest message. Same fingerprint, nothing happened. Different
 * fingerprint, a message arrived.
 */
class MessageDedup(private val capacity: Int = MAX_TRACKED) {

    private val lock = Any()

    /** Insertion-ordered, so the oldest conversation is the one evicted. */
    private val seen = LinkedHashMap<String, String>()

    /**
     * Whether the notification [key] now showing [fingerprint] is something new.
     *
     * Records it either way, so a repeat of the very next post is suppressed rather
     * than alternating.
     */
    fun isNew(key: String, fingerprint: String): Boolean = synchronized(lock) {
        if (seen[key] == fingerprint) return false
        seen.remove(key)
        seen[key] = fingerprint
        while (seen.size > capacity) seen.remove(seen.keys.first())
        true
    }

    /**
     * Forgets [key], because its notification is gone.
     *
     * Called when the user reads or swipes the chat away. Without it, a conversation
     * dismissed and then re-posted with the *same* last message — which is exactly
     * what happens when a messenger re-syncs after being offline — would be
     * suppressed as a duplicate of something the user has already dealt with.
     */
    fun forget(key: String) = synchronized(lock) { seen.remove(key); Unit }

    /** Test seam. */
    internal fun size(): Int = synchronized(lock) { seen.size }

    companion object {

        /**
         * How many conversations are remembered. Chosen the way [capacity] is
         * everywhere else here: comfortably more chats than any phone shows at once,
         * and small enough that the map is never worth thinking about.
         */
        const val MAX_TRACKED = 50
    }
}
