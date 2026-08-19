package com.example.ottomatic.engine.trigger

import com.example.ottomatic.core.model.NodeId
import com.example.ottomatic.core.trigger.TriggerEvent
import com.example.ottomatic.core.trigger.TriggerSource
import com.example.ottomatic.domain.model.WorkflowNode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `trigger.login_failed`'s threshold, and what it does with a count it could not read.
 */
class LoginFailedTriggerTest {

    private class FakeHost(private val events: List<TriggerEvent>) : TriggerHost {
        override fun busEvents(): Flow<TriggerEvent> = flowOf(*events.toTypedArray())

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

    private fun failure(attempts: String) = TriggerEvent(
        source = TriggerSource.SECURITY,
        triggerNodeId = NodeId.BROADCAST,
        payload = mapOf(LoginFailedTrigger.KEY_ATTEMPTS to attempts),
    )

    private val node = WorkflowNode(
        id = NodeId("n1"),
        typeId = LoginFailedTrigger.TYPE_ID,
        name = "Login Attempt Failed",
        x = 0f,
        y = 0f,
    )

    private suspend fun counts(after: Int, events: List<TriggerEvent>): List<Int> =
        LoginFailedTrigger()
            .activate(LoginFailedConfig(afterAttempts = after), node, FakeHost(events))
            .toList()
            .map { it.value.attempts }

    @Test
    fun `the default fires on every failed attempt`() = runBlocking {
        val events = listOf(failure("1"), failure("2"), failure("3"))
        assertEquals(listOf(1, 2, 3), counts(after = 1, events = events))
    }

    @Test
    fun `a threshold fires on that attempt and every one after it`() = runBlocking {
        val events = listOf(failure("1"), failure("2"), failure("3"), failure("4"))
        assertEquals(listOf(3, 4), counts(after = 3, events = events))
    }

    /**
     * The count read carries a conditional permission and can throw, in which case the
     * receiver reports -1. Firing at the default threshold is the choice that keeps the
     * commonest macro — "tell me about any failed unlock" — working, where a filter that
     * treated an unknown as zero would leave it silent forever with nothing saying why.
     */
    @Test
    fun `an unreadable count still fires at the default threshold`() = runBlocking {
        assertEquals(listOf(-1), counts(after = 1, events = listOf(failure("-1"))))
    }

    /** …and stays quiet where a number was actually asked for, rather than inventing one. */
    @Test
    fun `an unreadable count does not satisfy a real threshold`() = runBlocking {
        assertEquals(emptyList<Int>(), counts(after = 3, events = listOf(failure("-1"))))
    }

    /** The item keeps the reading; only the filter substitutes. */
    @Test
    fun `a missing payload reads as unknown rather than zero`() = runBlocking {
        val event = TriggerEvent(
            source = TriggerSource.SECURITY,
            triggerNodeId = NodeId.BROADCAST,
        )
        assertEquals(listOf(-1), counts(after = 1, events = listOf(event)))
    }

    @Test
    fun `events from another source are ignored`() = runBlocking {
        val other = TriggerEvent(
            source = TriggerSource.DISPLAY,
            triggerNodeId = NodeId.BROADCAST,
            payload = mapOf(LoginFailedTrigger.KEY_ATTEMPTS to "5"),
        )
        assertEquals(emptyList<Int>(), counts(after = 1, events = listOf(other)))
    }
}
