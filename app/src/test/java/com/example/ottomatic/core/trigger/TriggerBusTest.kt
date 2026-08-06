package com.example.ottomatic.core.trigger

import com.example.ottomatic.core.model.NodeId
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

    private companion object {
        const val TIMEOUT_MS = 300L
    }
}
