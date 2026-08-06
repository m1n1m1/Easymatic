package com.example.ottomatic.engine

import com.example.ottomatic.core.model.NodeId
import com.example.ottomatic.core.model.NodeTypeId
import com.example.ottomatic.core.trigger.TriggerEvent
import com.example.ottomatic.core.trigger.TriggerSource
import com.example.ottomatic.data.accessibility.OttomaticAccessibilityService as Service
import com.example.ottomatic.domain.model.WorkflowNode
import com.example.ottomatic.engine.trigger.BatteryDirection
import com.example.ottomatic.engine.trigger.GeofenceArmResult
import com.example.ottomatic.engine.trigger.GeofenceTransition
import com.example.ottomatic.engine.trigger.ScheduleHandle
import com.example.ottomatic.engine.trigger.TriggerHost
import com.example.ottomatic.engine.trigger.VolumeButtonConfig
import com.example.ottomatic.engine.trigger.VolumeButtonTrigger
import com.example.ottomatic.engine.trigger.keys.KeyGesture
import com.example.ottomatic.engine.trigger.keys.VolumeKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The seam between the accessibility service and the trigger is a
 * `Map<String, String>` payload, so nothing but a test stops the two sides from
 * drifting apart. These build events using the service's own key constants and
 * assert the trigger reads them.
 */
class VolumeButtonActivationTest {

    private val node = WorkflowNode(
        NodeId("n1"),
        NodeTypeId("trigger.volume_button"),
        "Volume",
        0f,
        0f,
    )

    @Test
    fun `three presses of the configured button fire once`() = runBlocking {
        val host = FakeKeyHost(
            keyEvent(Service.KEY_VOLUME_DOWN, Service.ACTION_DOWN, atMs = 0),
            keyEvent(Service.KEY_VOLUME_DOWN, Service.ACTION_UP, atMs = 100),
            keyEvent(Service.KEY_VOLUME_DOWN, Service.ACTION_DOWN, atMs = 400),
            keyEvent(Service.KEY_VOLUME_DOWN, Service.ACTION_UP, atMs = 500),
            keyEvent(Service.KEY_VOLUME_DOWN, Service.ACTION_DOWN, atMs = 800),
        )

        val emissions = VolumeButtonTrigger()
            .activate(VolumeButtonConfig(), node, host)
            .toList()

        assertEquals(1, emissions.size)
        val state = emissions.single().value
        assertEquals("sequence", state.event)
        assertEquals("volume_down", state.detail)
    }

    @Test
    fun `the other button does not fire the trigger`() = runBlocking {
        val host = FakeKeyHost(
            keyEvent(Service.KEY_VOLUME_UP, Service.ACTION_DOWN, atMs = 0),
            keyEvent(Service.KEY_VOLUME_UP, Service.ACTION_DOWN, atMs = 400),
            keyEvent(Service.KEY_VOLUME_UP, Service.ACTION_DOWN, atMs = 800),
        )

        val emissions = VolumeButtonTrigger()
            .activate(VolumeButtonConfig(key = VolumeKey.VOLUME_DOWN), node, host)
            .toList()

        assertTrue(emissions.isEmpty())
    }

    @Test
    fun `a single press fires in press mode`() = runBlocking {
        val host = FakeKeyHost(keyEvent(Service.KEY_VOLUME_UP, Service.ACTION_DOWN, atMs = 0))

        val emissions = VolumeButtonTrigger()
            .activate(
                VolumeButtonConfig(key = VolumeKey.VOLUME_UP, gesture = KeyGesture.PRESS),
                node,
                host,
            )
            .toList()

        assertEquals("press", emissions.single().value.event)
    }

    @Test
    fun `events from other sources are ignored`() = runBlocking {
        // The bus carries every trigger's events; a headset plug must not be
        // mistaken for a key press just because it is also HARDWARE-sourced.
        val host = FakeKeyHost(
            TriggerEvent(
                source = TriggerSource.HARDWARE,
                triggerNodeId = NodeId.BROADCAST,
                payload = mapOf("triggerType" to "headset", "event" to "plugged"),
            ),
        )

        val emissions = VolumeButtonTrigger()
            .activate(VolumeButtonConfig(gesture = KeyGesture.PRESS), node, host)
            .toList()

        assertTrue(emissions.isEmpty())
    }

    private fun keyEvent(key: String, action: String, atMs: Long) = TriggerEvent(
        source = TriggerSource.HARDWARE,
        triggerNodeId = NodeId.BROADCAST,
        payload = mapOf(
            Service.KEY_TRIGGER_TYPE to Service.TRIGGER_TYPE,
            Service.KEY_KEY to key,
            Service.KEY_ACTION to action,
            Service.KEY_REPEAT to "0",
            Service.KEY_EVENT_TIME to atMs.toString(),
        ),
    )
}

/** Replays a fixed list of bus events, then completes. */
private class FakeKeyHost(private vararg val events: TriggerEvent) : TriggerHost {

    override fun busEvents(): Flow<TriggerEvent> = events.toList().asFlow()

    override fun armSchedule(nodeId: NodeId, intervalMinutes: Long) = ScheduleHandle { }

    override fun armAlarm(nodeId: NodeId, atEpochMs: Long) = ScheduleHandle { }

    override fun armBatteryLevelPoll(
        nodeId: NodeId,
        intervalMinutes: Long,
        direction: BatteryDirection,
        threshold: Int,
    ) = ScheduleHandle { }

    override fun armGeofence(
        nodeId: NodeId,
        latitude: Double,
        longitude: Double,
        radiusMeters: Float,
        transitions: Set<GeofenceTransition>,
        dwellDelayMs: Int,
        onResult: (GeofenceArmResult) -> Unit,
    ) = ScheduleHandle { }
}
