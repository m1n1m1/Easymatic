package io.github.m1n1m1.easymatic.engine.trigger

import io.github.m1n1m1.easymatic.core.model.ConfigKey
import io.github.m1n1m1.easymatic.core.model.NodeId
import io.github.m1n1m1.easymatic.core.model.NodeTypeId
import io.github.m1n1m1.easymatic.core.service.AudioDeviceType
import io.github.m1n1m1.easymatic.core.trigger.TriggerEvent
import io.github.m1n1m1.easymatic.core.trigger.TriggerSource
import io.github.m1n1m1.easymatic.domain.model.WorkflowNode
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class AudioDeviceTriggerTest {
    private val trigger = HeadsetTrigger()
    private val node = WorkflowNode(NodeId("audio"), NodeTypeId("trigger.headset"), "Audio", 0f, 0f)
    private val events = listOf(
        event(AudioDeviceType.WIRED, "plugged"),
        event(AudioDeviceType.BLUETOOTH, "plugged"),
        event(AudioDeviceType.BLUETOOTH, "unplugged"),
        event(AudioDeviceType.DOCK, "plugged"),
    )

    @Test
    fun `type and event filters are applied together`() = runBlocking {
        val config = AudioDeviceTriggerConfig(HeadsetEvent.UNPLUGGED, AudioDeviceType.BLUETOOTH)
        val outputs = trigger.activate(config, node, AudioHost(events)).toList()
        assertEquals(listOf("unplugged"), outputs.map { it.value.event })
        assertEquals("BLUETOOTH", outputs.single().value.detail)
    }

    @Test
    fun `any type receives all external output changes and ignores unrelated events`() = runBlocking {
        val unrelated = events.first().copy(source = TriggerSource.CONNECTIVITY)
        val outputs = trigger.activate(AudioDeviceTriggerConfig(), node, AudioHost(events + unrelated)).toList()
        assertEquals(events.size, outputs.size)
    }

    @Test
    fun `old event settings retain their meaning and default to any device type`() {
        for (event in HeadsetEvent.entries) {
            val decoded = trigger.definition.schema.decode(mapOf(ConfigKey("event") to event.name))
            assertEquals(AudioDeviceTriggerConfig(event), decoded)
        }
        assertEquals(AudioDeviceTriggerConfig(), trigger.definition.schema.decode(emptyMap()))
    }

    private fun event(type: AudioDeviceType, event: String) = TriggerEvent(
        source = TriggerSource.HARDWARE,
        triggerNodeId = NodeId.BROADCAST,
        payload = mapOf("triggerType" to "headset", "event" to event, "deviceType" to type.name, "detail" to type.name),
    )
}

private class AudioHost(private val events: List<TriggerEvent>) : TriggerHost {
    override fun busEvents() = events.asFlow()
    override fun armSchedule(nodeId: NodeId, intervalMinutes: Long) = ScheduleHandle {}
    override fun armAlarm(nodeId: NodeId, atEpochMs: Long) = ScheduleHandle {}
    override fun armBatteryLevelPoll(
        nodeId: NodeId,
        intervalMinutes: Long,
        direction: BatteryDirection,
        threshold: Int,
    ) = ScheduleHandle {}

    override fun armGeofence(
        nodeId: NodeId,
        latitude: Double,
        longitude: Double,
        radiusMeters: Float,
        transitions: Set<GeofenceTransition>,
        dwellDelayMs: Int,
        onResult: (GeofenceArmResult) -> Unit,
    ) = ScheduleHandle {}
}
