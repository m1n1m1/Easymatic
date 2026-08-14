package com.example.ottomatic.data.homeassistant

import java.util.concurrent.ConcurrentHashMap

/**
 * Which event types one connection is subscribed to, and which it still has to ask for.
 *
 * Split out of [HaSocket] and made pure so the one invariant here can be tested without
 * a server: **`state_changed` is subscribed exactly once.** That is easy to get wrong and
 * the way it goes wrong is invisible from the socket's own point of view — Home Assistant
 * happily accepts two subscriptions to the same type and then delivers every matching
 * event *twice*, once per subscription id. A `trigger.ha_event` watching `state_changed`
 * would fire twice for every change in the house, and nothing in the frame log would look
 * out of place.
 *
 * The type is special because it is wanted for two independent reasons: the state cache
 * behind `value.ha_state` is built from it whether or not anything is armed, and a
 * `trigger.ha_event` may also name it. Treating those as one interest is what makes the
 * second a no-op, and is why [unwatch] refuses to drop it — a node disarming must not take
 * the cache down with it.
 */
internal class HaSubscriptions {

    /** Every type wanted, including the one wanted unconditionally. */
    private val wanted = ConcurrentHashMap.newKeySet<String>()

    /** The subscription id the server gave each type, so it can be dropped again. */
    private val ids = ConcurrentHashMap<String, Int>()

    init {
        // Wanted from the moment the connection exists, because the cache depends on it.
        wanted.add(ALWAYS)
    }

    /** Everything to subscribe to on a fresh connection, in a stable order. */
    fun onConnect(): List<String> {
        ids.clear()
        return wanted.sorted()
    }

    /**
     * Records that [eventType] is wanted, and answers whether a subscription has to be
     * sent for it now.
     *
     * False for a type already wanted — several trigger nodes on one type cost one
     * subscription between them, which is `MailWatchers`' per-mailbox refcount applied to
     * an event type — and false for [ALWAYS], which is already subscribed.
     */
    fun add(eventType: String): Boolean = eventType.isNotBlank() && wanted.add(eventType)

    /**
     * Forgets [eventType] and answers the subscription id to cancel, or null when there
     * is nothing to send.
     *
     * Null for [ALWAYS] whatever the caller asks, because the cache goes on needing it
     * after the last node watching it has gone.
     */
    fun remove(eventType: String): Int? {
        if (eventType == ALWAYS || !wanted.remove(eventType)) return null
        return ids.remove(eventType)
    }

    /** Notes the id the server assigned when [eventType] was subscribed. */
    fun assigned(eventType: String, id: Int) {
        ids[eventType] = id
    }

    internal companion object {
        /** Subscribed on every connection, armed or not: the state cache is built from it. */
        const val ALWAYS = HaMessages.STATE_CHANGED
    }
}
