package com.example.ottomatic.engine.trigger

import com.example.ottomatic.core.model.NodeId
import com.example.ottomatic.core.model.NodeTypeId
import com.example.ottomatic.core.trigger.TriggerEvent
import com.example.ottomatic.core.trigger.TriggerSource
import com.example.ottomatic.domain.model.PhoneRef
import com.example.ottomatic.domain.model.WorkflowNode
import com.example.ottomatic.engine.trigger.GeofenceArmResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `trigger.sms`'s sender filter over the two things a phone field can hold.
 *
 * The filter used to be string equality, which never matched: an SMS arrives as
 * `+436761234567` while people write `0676 123 4567`.
 */
class SmsTriggerFilterTest {

    private class FakeHost(
        private val events: List<TriggerEvent>,
        private val numbers: Map<String, String> = emptyMap(),
    ) : TriggerHost {
        override fun busEvents(): Flow<TriggerEvent> = flowOf(*events.toTypedArray())

        override fun contactNumber(lookupKey: String): String? = numbers[lookupKey]

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

    private fun smsFrom(sender: String) = TriggerEvent(
        source = TriggerSource.SMS,
        triggerNodeId = NodeId.BROADCAST,
        payload = mapOf(SmsTrigger.KEY_SENDER to sender, SmsTrigger.KEY_BODY to "hi"),
    )

    private val node = WorkflowNode(
        id = NodeId("n1"),
        typeId = NodeTypeId("trigger.sms"),
        name = "SMS",
        x = 0f,
        y = 0f,
    )

    private suspend fun senders(config: SmsTriggerConfig, host: FakeHost): List<String> =
        SmsTrigger().activate(config, node, host).toList().map { it.value.sender }

    @Test
    fun `a blank filter passes every sender`() = runBlocking {
        val host = FakeHost(listOf(smsFrom("+436761234567"), smsFrom("+4915112345678")))
        assertEquals(
            listOf("+436761234567", "+4915112345678"),
            senders(SmsTriggerConfig(sender = ""), host),
        )
    }

    @Test
    fun `a typed filter matches the same number written differently`() = runBlocking {
        val host = FakeHost(listOf(smsFrom("+436761234567"), smsFrom("+4915112345678")))
        assertEquals(
            listOf("+436761234567"),
            senders(SmsTriggerConfig(sender = "0676 123 4567"), host),
        )
    }

    @Test
    fun `a contact filter matches through the host`() = runBlocking {
        val host = FakeHost(
            events = listOf(smsFrom("+436761234567"), smsFrom("+4915112345678")),
            numbers = mapOf("0r3-2A" to "0676/1234567"),
        )
        assertEquals(
            listOf("+436761234567"),
            senders(SmsTriggerConfig(sender = PhoneRef.contactSpec("0r3-2A", "Mum")), host),
        )
    }

    /**
     * Fails closed. A filter that widened to "any sender" when the contact could not
     * be read would run the macro on every text from anyone, which is much worse
     * than not running.
     */
    @Test
    fun `an unresolvable contact filter matches nothing`() = runBlocking {
        val host = FakeHost(listOf(smsFrom("+436761234567")))
        assertEquals(
            emptyList<String>(),
            senders(SmsTriggerConfig(sender = PhoneRef.contactSpec("gone", "Ghost")), host),
        )
    }
}
