package com.example.ottomatic.data.notification

import com.example.ottomatic.core.service.NotificationAnswer
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * The notifications this app has posted and is still waiting on, by token.
 *
 * A process-wide `object` on `AndroidWaits.pending`'s reasoning, which is also
 * `ActiveNotifications`': the thing that delivers the answer is a
 * [NotificationResponseReceiver] the **system** constructs, so it has no route to
 * whichever [AndroidNotifications] posted the notification and nothing can be handed
 * to it. A static map is what bridges that, and it is in memory for the same stated
 * reason — a `PendingIntent` that outlives the process arrives to an empty map and
 * resumes nothing, which is exactly the durability `ForkAction` promises and no more.
 *
 * ### Why a token and not the tag
 *
 * A tag is the *notification's* identity and is deliberately reusable: posting under
 * one replaces what was there. A token is the **wait's** identity, and two waits on
 * one tag are ordinary — a macro that re-posts a countdown every minute has a
 * superseded wait behind each earlier post. Keying on the tag would hand the newest
 * notification's button press to the oldest waiter still listening.
 */
internal object NotificationResponses {

    private val tokens = AtomicLong()

    /**
     * Request codes for the `PendingIntent`s, which must differ per button.
     *
     * `PendingIntent` equality ignores extras entirely, so two actions on one
     * notification built with the same request code are **one** `PendingIntent`:
     * under `FLAG_UPDATE_CURRENT` the second overwrites the first's extras and both
     * buttons then report whichever index was written last. A counter is the cheapest
     * thing that cannot collide.
     */
    private val requestCodes = AtomicInteger()

    private val lock = Any()

    /**
     * Insertion-ordered and bounded, `ActiveNotifications`' shape for a reason of its
     * own: a registration is normally taken out again by [deliver] or by `await`'s
     * `finally`, but not always. A fork refused by `MAX_PENDING_WAITS` never calls
     * `await` at all, so its entry would sit here for the life of the process — a slow
     * leak that only shows up on the phone of somebody whose macro loops.
     *
     * Eviction **completes** the evicted waiter with null rather than dropping it, so
     * anything that was listening gives up instead of hanging on a notification this
     * object has stopped tracking.
     */
    private val waiting = LinkedHashMap<Long, CompletableDeferred<NotificationAnswer?>>()

    /** A token nothing has used before. */
    fun nextToken(): Long = tokens.incrementAndGet()

    /** A request code nothing has used before. */
    fun nextRequestCode(): Int = requestCodes.incrementAndGet()

    /**
     * The waiter for [token], creating it the first time.
     *
     * Called **twice** for every answerable notification — once as it is posted, once
     * as the node begins to await — and it has to be the same object both times, which
     * is why this is `computeIfAbsent` rather than an assignment. The two calls are
     * different moments with the user in between: the notification is on screen from
     * the first, so a tap landing before the second must complete something the second
     * then finds already done. Overwriting would drop that press on the floor and leave
     * the branch waiting for a notification nobody will touch again.
     */
    fun register(token: Long): CompletableDeferred<NotificationAnswer?> = synchronized(lock) {
        waiting.getOrPut(token) { CompletableDeferred() }.also {
            while (waiting.size > MAX_WAITING) {
                waiting.remove(waiting.keys.first())?.complete(null)
            }
        }
    }

    /**
     * Hands [answer] to whoever is waiting on [token] — **null meaning "it went away
     * unanswered"**, which is what a swipe delivers.
     *
     * Idempotent: `remove` takes the waiter out first, so a second delivery for the
     * same token (a swipe racing a button press) finds nothing and does nothing.
     */
    fun deliver(token: Long, answer: NotificationAnswer?) {
        synchronized(lock) { waiting.remove(token) }?.complete(answer)
    }

    /** Stops listening for [token], without delivering anything. */
    fun forget(token: Long) {
        synchronized(lock) { waiting.remove(token) }
    }

    /** How many waits are outstanding. For tests, which have no other way to see the leak. */
    val pending: Int get() = synchronized(lock) { waiting.size }

    /**
     * How many notifications stay answerable at once.
     *
     * Above `MAX_PENDING_WAITS`, so a phone at the executor's own fork cap never has a
     * live wait evicted from under it: this bound is here for the entries that cap
     * *refused*, not for the ones it allowed.
     */
    private const val MAX_WAITING = 128
}
