package com.example.ottomatic.data.homeassistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The subscription set, and the one invariant it exists for.
 *
 * This is split out of `HaSocket` purely so it can be tested, because the bug it prevents is
 * invisible from the socket's own point of view: Home Assistant accepts two subscriptions to
 * the same event type and then delivers every matching event **twice**, once per subscription
 * id, with nothing in the frame log looking out of place. A `trigger.ha_event` watching
 * `state_changed` fired twice for every change in the house.
 *
 * It had a second half that was worse and quieter. The duplicate `subscribe` overwrote the
 * stored id, so the *first* subscription could never be cancelled by anything — which is
 * exactly why the state cache went on working and the double firing was the only symptom.
 */
class HaSubscriptionsTest {

    /**
     * **The load-bearing one.** `state_changed` is wanted unconditionally because the cache
     * behind `value.ha_state` is built from it, *and* a trigger may name it. Those are one
     * interest, not two, and this is the assertion that says so.
     */
    @Test
    fun `state_changed is subscribed exactly once, even when a node also watches it`() {
        val subscriptions = HaSubscriptions()

        assertEquals(listOf(HaMessages.STATE_CHANGED), subscriptions.onConnect())

        // A trigger.ha_event pointed at it. This used to answer true and subscribe again.
        assertFalse(subscriptions.add(HaMessages.STATE_CHANGED))
        assertEquals(listOf(HaMessages.STATE_CHANGED), subscriptions.onConnect())
    }

    /**
     * A node disarming must not take the cache's subscription down with it — the cache goes on
     * needing `state_changed` after the last trigger watching it has gone.
     */
    @Test
    fun `state_changed is never unsubscribed, whatever the caller asks`() {
        val subscriptions = HaSubscriptions()
        subscriptions.assigned(HaMessages.STATE_CHANGED, id = 2)

        assertNull(subscriptions.remove(HaMessages.STATE_CHANGED))
        assertTrue(subscriptions.onConnect().contains(HaMessages.STATE_CHANGED))
    }

    /**
     * `MailWatchers`' per-mailbox refcount, applied to an event type: several trigger nodes on
     * one type must cost one subscription between them.
     */
    @Test
    fun `a type wanted twice is subscribed once`() {
        val subscriptions = HaSubscriptions()

        assertTrue(subscriptions.add("zha_event"))
        assertFalse(subscriptions.add("zha_event"))
        assertEquals(1, subscriptions.onConnect().count { it == "zha_event" })
    }

    @Test
    fun `two types are two subscriptions`() {
        val subscriptions = HaSubscriptions()
        subscriptions.add("zha_event")
        subscriptions.add("tag_scanned")

        assertEquals(
            listOf(HaMessages.STATE_CHANGED, "tag_scanned", "zha_event"),
            subscriptions.onConnect().sorted(),
        )
    }

    @Test
    fun `a blank type is not a subscription`() {
        val subscriptions = HaSubscriptions()

        assertFalse(subscriptions.add(""))
        assertFalse(subscriptions.add("   "))
        assertEquals(listOf(HaMessages.STATE_CHANGED), subscriptions.onConnect())
    }

    @Test
    fun `dropping a type yields the id to cancel, and only once`() {
        val subscriptions = HaSubscriptions()
        subscriptions.add("zha_event")
        subscriptions.assigned("zha_event", id = 7)

        assertEquals(7, subscriptions.remove("zha_event"))
        assertNull(subscriptions.remove("zha_event"))
        assertFalse(subscriptions.onConnect().contains("zha_event"))
    }

    /** Dropping something never wanted is not an error and sends nothing. */
    @Test
    fun `dropping an unwatched type sends nothing`() {
        assertNull(HaSubscriptions().remove("never_wanted"))
    }

    /**
     * Message ids restart at 1 on every connection — `HaSocket`'s KDoc says so — so an id from
     * the old connection would unsubscribe something the new one never issued. The wants
     * survive a reconnect; the ids must not.
     */
    @Test
    fun `a reconnect keeps the wants and forgets the ids`() {
        val subscriptions = HaSubscriptions()
        subscriptions.add("zha_event")
        subscriptions.assigned("zha_event", id = 4)

        assertTrue(subscriptions.onConnect().contains("zha_event"))
        assertNull(subscriptions.remove("zha_event"))
    }
}
