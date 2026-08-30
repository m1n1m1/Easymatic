package io.github.m1n1m1.easymatic.engine.trigger

import io.github.m1n1m1.easymatic.core.model.NodeId
import io.github.m1n1m1.easymatic.core.model.NodeTypeId
import io.github.m1n1m1.easymatic.core.trigger.CallPayload
import io.github.m1n1m1.easymatic.core.trigger.TriggerEvent
import io.github.m1n1m1.easymatic.core.trigger.TriggerSource
import io.github.m1n1m1.easymatic.domain.model.WorkflowNode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What the two call triggers let through.
 *
 * The load-bearing assertions are that `trigger.call_state`'s **event filter actually
 * matches**, and that `trigger.call_ended` can tell a missed call from an answered one.
 * The first is a regression test with a real bug behind it: the payload used to carry the
 * telephony broadcast's `"RINGING"` verbatim while the trigger compared it against the
 * enum entry lower-cased, so every selected filter matched nothing and only the unset
 * "Any" option ever fired. Nothing failed, and the node looked armed.
 */
class CallTriggerFilterTest {

    private class FakeHost(private val events: List<TriggerEvent>) : TriggerHost {
        override fun busEvents(): Flow<TriggerEvent> = flowOf(*events.toTypedArray())

        override fun busEventsFor(nodeId: NodeId): Flow<TriggerEvent> = busEvents()

        override fun armGeofence(
            nodeId: NodeId,
            latitude: Double,
            longitude: Double,
            radiusMeters: Float,
            transitions: Set<GeofenceTransition>,
            dwellDelayMs: Int,
            onResult: (GeofenceArmResult) -> Unit,
        ): ScheduleHandle = ScheduleHandle {}

        override fun armSchedule(nodeId: NodeId, intervalMinutes: Long) = ScheduleHandle {}

        override fun armAlarm(nodeId: NodeId, atEpochMs: Long) = ScheduleHandle {}

        override fun armBatteryLevelPoll(
            nodeId: NodeId,
            intervalMinutes: Long,
            direction: BatteryDirection,
            threshold: Int,
        ) = ScheduleHandle {}
    }

    private fun call(
        state: String,
        packageName: String = "com.microsoft.teams",
        answered: Boolean = false,
        incoming: Boolean = true,
        durationSeconds: Int = 0,
    ) = TriggerEvent(
        source = TriggerSource.CALL,
        triggerNodeId = NodeId.BROADCAST,
        payload = mapOf(
            CallPayload.KEY_STATE to state,
            CallPayload.KEY_CALLER to "Rita",
            CallPayload.KEY_APP_NAME to "Teams",
            CallPayload.KEY_PACKAGE to packageName,
            CallPayload.KEY_INCOMING to incoming.toString(),
            CallPayload.KEY_VIDEO to "false",
            CallPayload.KEY_ANSWERED to answered.toString(),
            CallPayload.KEY_DURATION_SECONDS to durationSeconds.toString(),
            CallPayload.KEY_TIMESTAMP to "0",
        ),
    )

    private val node = WorkflowNode(
        id = NodeId("n1"),
        typeId = NodeTypeId("trigger.call_state"),
        name = "Call State",
        x = 0f,
        y = 0f,
    )

    private val everyPhase = listOf(call("ringing"), call("active"), call("ended", answered = true))

    @Test
    fun `an unset filter fires on every phase`() = runBlocking {
        val fired = CallStateTrigger()
            .activate(CallStateConfig(), node, FakeHost(everyPhase))
            .toList()

        assertEquals(listOf("ringing", "active", "ended"), fired.map { it.value.state })
    }

    /** The regression: a selected filter used to match nothing at all. */
    @Test
    fun `a selected event filter matches that phase and only that phase`() = runBlocking {
        val fired = CallStateTrigger()
            .activate(CallStateConfig(event = CallStateEvent.RINGING), node, FakeHost(everyPhase))
            .toList()

        assertEquals(1, fired.size)
        assertEquals("ringing", fired.single().value.state)
        assertEquals("Rita", fired.single().value.caller)
    }

    @Test
    fun `an app filter compares the package exactly`() = runBlocking {
        val events = listOf(call("ringing"), call("ringing", packageName = "com.discord"))

        val fired = CallStateTrigger()
            .activate(CallStateConfig(packageFilter = "com.discord"), node, FakeHost(events))
            .toList()

        assertEquals(1, fired.size)
        assertEquals("com.discord", fired.single().value.packageName)
    }

    @Test
    fun `call ended ignores every phase but the last`() = runBlocking {
        val fired = CallEndedTrigger()
            .activate(CallEndedConfig(), node, FakeHost(everyPhase))
            .toList()

        assertEquals(1, fired.size)
        assertEquals("ended", fired.single().value.state)
    }

    /** A missed call is a call that ended having never been answered. */
    @Test
    fun `the outcome filter tells a missed call from an answered one`() = runBlocking {
        val events = listOf(
            call("ended", answered = true, durationSeconds = 91),
            call("ended", answered = false),
        )

        val missed = CallEndedTrigger()
            .activate(CallEndedConfig(outcome = CallOutcome.MISSED), node, FakeHost(events))
            .toList()
        val answered = CallEndedTrigger()
            .activate(CallEndedConfig(outcome = CallOutcome.ANSWERED), node, FakeHost(events))
            .toList()

        assertEquals(1, missed.size)
        assertEquals(0, missed.single().value.durationSeconds)
        assertEquals(1, answered.size)
        assertEquals(91, answered.single().value.durationSeconds)
    }

    @Test
    fun `the direction filter separates calls placed from calls received`() = runBlocking {
        val events = listOf(call("ended", incoming = true), call("ended", incoming = false))

        val outgoing = CallEndedTrigger()
            .activate(CallEndedConfig(direction = CallDirection.OUTGOING), node, FakeHost(events))
            .toList()

        assertEquals(1, outgoing.size)
        assertEquals(false, outgoing.single().value.incoming)
    }

    /**
     * `trigger.notification` and `trigger.message` both read the same stream, and a call
     * is not either of them. Nothing on another source may reach these.
     */
    @Test
    fun `an event on another source is ignored`() = runBlocking {
        val notCall = TriggerEvent(
            source = TriggerSource.NOTIFICATION,
            triggerNodeId = NodeId.BROADCAST,
            payload = mapOf(CallPayload.KEY_STATE to "ringing"),
        )

        val fired = CallStateTrigger().activate(CallStateConfig(), node, FakeHost(listOf(notCall))).toList()

        assertEquals(emptyList<Any>(), fired)
    }
}
