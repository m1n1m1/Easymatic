package io.github.m1n1m1.easymatic.core.trigger

import io.github.m1n1m1.easymatic.core.model.NodeId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The hand-off buffer that keeps a geofence transition alive across a cold start.
 *
 * The two assertions everything else is built around: an event held while nobody
 * was listening reaches the trigger that arms next, and it reaches it **once**.
 * The second is what stops a macro running twice for one crossing, and it is why
 * [TriggerBus.emitOrHold] exists rather than `replay = 1`.
 *
 * [TriggerBus] is a process-wide object, so the held map is cleared around every
 * test the way `VariableStore.clear()` is.
 */
class TriggerBusTest {

    private val node = NodeId("n1")
    private val other = NodeId("n2")

    @Before
    fun setUp() = TriggerBus.clearHeld()

    @After
    fun tearDown() = TriggerBus.clearHeld()

    private fun event(
        nodeId: NodeId = node,
        firedAtEpochMs: Long = System.currentTimeMillis(),
    ) = TriggerEvent(
        source = TriggerSource.GEOFENCE,
        triggerNodeId = nodeId,
        payload = mapOf("event" to "enter"),
        firedAtEpochMs = firedAtEpochMs,
    )

    /**
     * Everything [nodeId] is handed within [TIMEOUT_MS].
     *
     * The flow never completes — it is a shared bus — so a timeout is how a drain
     * is read at all. The held events are emitted inside `onSubscription`, i.e.
     * before anything can suspend, so this returns them without ever waiting the
     * full timeout when there is something to collect.
     */
    private suspend fun drain(nodeId: NodeId): List<TriggerEvent> {
        val seen = mutableListOf<TriggerEvent>()
        withTimeoutOrNull(TIMEOUT_MS) { TriggerBus.eventsFor(nodeId).collect { seen += it } }
        return seen
    }

    /**
     * Pins the `replay = 0` contract the hold buffer must not quietly break — a
     * replaying bus would re-fire every fence on every geofence-place edit.
     */
    @Test
    fun `an event emitted with nobody listening is gone`() = runBlocking {
        TriggerBus.emit(event())

        assertTrue("plain emit must not resurrect an event for a later subscriber", drain(node).isEmpty())
    }

    @Test
    fun `a held event reaches the trigger that arms next`() = runBlocking {
        TriggerBus.emitOrHold(event())

        val received = drain(node).single()

        assertEquals(node, received.triggerNodeId)
        assertEquals("enter", received.payload["event"])
    }

    /** So the trigger can say it is acting on something that arrived while it woke up. */
    @Test
    fun `a held event is marked as held`() = runBlocking {
        TriggerBus.emitOrHold(event())

        assertEquals("true", drain(node).single().payload[TriggerBus.KEY_HELD])
    }

    /** The no-double-run guarantee: a re-arm must not replay what already ran. */
    @Test
    fun `a held event is delivered once`() = runBlocking {
        TriggerBus.emitOrHold(event())
        drain(node)

        assertTrue("a second arm of the same node must not see the event again", drain(node).isEmpty())
    }

    /**
     * An event held while the engine never came up must be gone when the user
     * opens the app an hour later — a transition that old is history, not an
     * arrival.
     */
    @Test
    fun `a held event older than the cap never fires`() = runBlocking {
        TriggerBus.emitOrHold(event(firedAtEpochMs = System.currentTimeMillis() - TriggerBus.HOLD_MAX_AGE_MS - 1))

        assertTrue("a stale transition must not start a macro", drain(node).isEmpty())
    }

    @Test
    fun `a held event is addressed to one node`() = runBlocking {
        TriggerBus.emitOrHold(event(nodeId = node))

        assertTrue("another node's arm must not consume this event", drain(other).isEmpty())
    }

    /**
     * The exclusivity that makes "delivered or held, never both" true. Without it
     * one crossing would run the macro live *and* again on the next arm.
     */
    @Test
    fun `an event delivered live is not also held`() = runBlocking {
        val delivered = mutableListOf<TriggerEvent>()
        val ready = Channel<Unit>(capacity = 1)
        val collector = launch(Dispatchers.Default) {
            TriggerBus.events.onSubscription { ready.send(Unit) }.collect { delivered += it }
        }
        ready.receive()

        TriggerBus.emitOrHold(event())
        withTimeoutOrNull(TIMEOUT_MS) { while (delivered.isEmpty()) yield() }
        collector.cancelAndJoin()

        assertEquals("the live subscriber must get it", 1, delivered.size)
        assertTrue("and it must not also be waiting for the next arm", drain(node).isEmpty())
    }

    /** A receiver firing in a loop must not grow the map without bound. */
    @Test
    fun `the hold buffer is bounded per node`() = runBlocking {
        repeat(TriggerBus.MAX_HELD_PER_NODE * 2) { TriggerBus.emitOrHold(event()) }

        assertEquals(TriggerBus.MAX_HELD_PER_NODE, drain(node).size)
    }

    // ---- Broadcasts --------------------------------------------------------
    //
    // The fan-out half, which `emitOrHold` cannot serve: it parks under the event's
    // node id, and a `NodeId.BROADCAST` event parked there is drained by nothing at
    // all, because no trigger ever collects `eventsFor(BROADCAST)`.

    /**
     * The failure this exists for. An NFC tap, a boot and an SMS all arrive at the
     * one moment nothing can be subscribed — the event *is* what started the process
     * — so a plain emit is discarded and the macro never runs.
     */
    @Test
    fun `a broadcast held while starting reaches the node that arms next`() = runBlocking {
        TriggerBus.emitOrHoldBroadcast(broadcast())

        assertEquals("true", drain(node).single().payload[TriggerBus.KEY_HELD])
    }

    /**
     * The reason a broadcast is parked *and* emitted rather than one or the other:
     * `rearmAll` arms macros one at a time, so "somebody is subscribed" is no
     * evidence that the node this matters to is.
     */
    @Test
    fun `every node gets its own copy, and only one`() = runBlocking {
        TriggerBus.emitOrHoldBroadcast(broadcast())

        assertEquals(1, drain(node).size)
        assertEquals("a second node arming later must get it too", 1, drain(other).size)
        assertTrue("but neither may take it twice", drain(node).isEmpty())
    }

    /** Nothing parks once the engine has finished arming. */
    @Test
    fun `a broadcast after engineReady is not held`() = runBlocking {
        TriggerBus.engineReady()
        TriggerBus.emitOrHoldBroadcast(broadcast())

        assertTrue(drain(node).isEmpty())
    }

    /**
     * `engineReady` deliberately leaves the queue alone — the last-armed macro has
     * probably not subscribed yet — so the age cap is what finally clears it, and it
     * is much shorter than the per-node one: a tap this old is no longer something
     * the user just did.
     */
    @Test
    fun `a broadcast older than the cap never fires`() = runBlocking {
        val stale = System.currentTimeMillis() - TriggerBus.BROADCAST_HOLD_MAX_AGE_MS - 1
        TriggerBus.emitOrHoldBroadcast(broadcast(firedAtEpochMs = stale))

        assertTrue(drain(node).isEmpty())
    }

    /** A tag tapped repeatedly must not grow the queue without bound. */
    @Test
    fun `the broadcast buffer is bounded`() = runBlocking {
        repeat(BROADCAST_FLOOD) { TriggerBus.emitOrHoldBroadcast(broadcast()) }

        assertTrue("must be capped well below the flood", drain(node).size < BROADCAST_FLOOD)
    }

    private fun broadcast(firedAtEpochMs: Long = System.currentTimeMillis()) = TriggerEvent(
        source = TriggerSource.NFC,
        triggerNodeId = NodeId.BROADCAST,
        payload = mapOf("tagId" to "04A23F1B"),
        firedAtEpochMs = firedAtEpochMs,
    )

    private companion object {
        const val TIMEOUT_MS = 300L
        const val BROADCAST_FLOOD = 32
    }
}
