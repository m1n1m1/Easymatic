package io.github.m1n1m1.easymatic.engine

import io.github.m1n1m1.easymatic.core.model.NodeId
import io.github.m1n1m1.easymatic.core.model.NodeTypeId
import io.github.m1n1m1.easymatic.core.trigger.TriggerEvent
import io.github.m1n1m1.easymatic.core.trigger.TriggerSource
import io.github.m1n1m1.easymatic.data.accessibility.EasymaticAccessibilityService as Service
import io.github.m1n1m1.easymatic.domain.model.WorkflowNode
import io.github.m1n1m1.easymatic.engine.trigger.BatteryDirection
import io.github.m1n1m1.easymatic.engine.trigger.GeofenceArmResult
import io.github.m1n1m1.easymatic.engine.trigger.GeofenceTransition
import io.github.m1n1m1.easymatic.engine.trigger.ScheduleHandle
import io.github.m1n1m1.easymatic.engine.trigger.TriggerHost
import io.github.m1n1m1.easymatic.engine.trigger.VolumeButtonConfig
import io.github.m1n1m1.easymatic.engine.trigger.VolumeButtonTrigger
import io.github.m1n1m1.easymatic.engine.trigger.keys.KeyGesture
import io.github.m1n1m1.easymatic.engine.trigger.keys.VolumeKey
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
