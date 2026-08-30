package io.github.m1n1m1.easymatic.engine

import io.github.m1n1m1.easymatic.core.model.NodeId
import io.github.m1n1m1.easymatic.core.model.NodeTypeId
import io.github.m1n1m1.easymatic.core.trigger.TriggerEvent
import io.github.m1n1m1.easymatic.core.trigger.TriggerSource
import io.github.m1n1m1.easymatic.data.accessibility.EasymaticAccessibilityService as Service
import io.github.m1n1m1.easymatic.domain.model.WorkflowNode
import io.github.m1n1m1.easymatic.engine.trigger.BatteryDirection
import io.github.m1n1m1.easymatic.engine.trigger.FingerprintGestureConfig
import io.github.m1n1m1.easymatic.engine.trigger.FingerprintGestureTrigger
import io.github.m1n1m1.easymatic.engine.trigger.GeofenceArmResult
import io.github.m1n1m1.easymatic.engine.trigger.GeofenceTransition
import io.github.m1n1m1.easymatic.engine.trigger.ScheduleHandle
import io.github.m1n1m1.easymatic.engine.trigger.TriggerHost
import io.github.m1n1m1.easymatic.engine.trigger.gesture.FingerprintGesture
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The seam between the accessibility service and the trigger is a
 * `Map<String, String>` payload, so nothing but a test stops the two sides drifting
 * apart. These build events from the service's own constants and assert the trigger
 * reads them, exactly as `VolumeButtonActivationTest` does one payload over.
 *
 * The load-bearing assertion is the **unset filter**: an unconfigured node must fire
 * on all four swipes rather than on none, since that is what the blank "Any" option
 * in the form promises.
 */
class FingerprintGestureActivationTest {

    private val node = WorkflowNode(
        NodeId("n1"),
        NodeTypeId("trigger.fingerprint_gesture"),
        "Fingerprint",
        0f,
        0f,
    )

    @Test
    fun `an unconfigured node fires on every swipe direction`() = runBlocking {
        val host = FakeGestureHost(
            gestureEvent(Service.GESTURE_SWIPE_UP),
            gestureEvent(Service.GESTURE_SWIPE_DOWN),
            gestureEvent(Service.GESTURE_SWIPE_LEFT),
            gestureEvent(Service.GESTURE_SWIPE_RIGHT),
        )

        val emissions = FingerprintGestureTrigger()
            .activate(FingerprintGestureConfig(), node, host)
            .toList()

        assertEquals(
            listOf("swipe_up", "swipe_down", "swipe_left", "swipe_right"),
            emissions.map { it.value.event },
        )
    }

    @Test
    fun `a chosen gesture drops the other three`() = runBlocking {
        val host = FakeGestureHost(
            gestureEvent(Service.GESTURE_SWIPE_UP),
            gestureEvent(Service.GESTURE_SWIPE_DOWN),
            gestureEvent(Service.GESTURE_SWIPE_LEFT),
            gestureEvent(Service.GESTURE_SWIPE_RIGHT),
        )

        val emissions = FingerprintGestureTrigger()
            .activate(FingerprintGestureConfig(gesture = FingerprintGesture.SWIPE_DOWN), node, host)
            .toList()

        assertEquals("swipe_down", emissions.single().value.event)
    }

    @Test
    fun `a gesture nobody recognises is ignored rather than emitted blank`() = runBlocking {
        // A future platform constant, or a corrupt payload. Emitting a SystemState
        // with an empty `event` would look to everything downstream like a real swipe.
        val host = FakeGestureHost(gestureEvent("swipe_sideways"))

        val emissions = FingerprintGestureTrigger()
            .activate(FingerprintGestureConfig(), node, host)
            .toList()

        assertTrue(emissions.isEmpty())
    }

    @Test
    fun `events from other sources are ignored`() = runBlocking {
        // The bus carries every trigger's events, and the volume path is HARDWARE-sourced
        // too — so the triggerType discriminator is the only thing separating them.
        val host = FakeGestureHost(
            TriggerEvent(
                source = TriggerSource.HARDWARE,
                triggerNodeId = NodeId.BROADCAST,
                payload = mapOf(
                    Service.KEY_TRIGGER_TYPE to Service.TRIGGER_TYPE,
                    Service.KEY_KEY to Service.KEY_VOLUME_DOWN,
                    Service.KEY_ACTION to Service.ACTION_DOWN,
                ),
            ),
        )

        val emissions = FingerprintGestureTrigger()
            .activate(FingerprintGestureConfig(), node, host)
            .toList()

        assertTrue(emissions.isEmpty())
    }

    private fun gestureEvent(gesture: String) = TriggerEvent(
        source = TriggerSource.HARDWARE,
        triggerNodeId = NodeId.BROADCAST,
        payload = mapOf(
            Service.KEY_TRIGGER_TYPE to Service.FINGERPRINT_TRIGGER_TYPE,
            Service.KEY_GESTURE to gesture,
        ),
    )
}

/** Replays a fixed list of bus events, then completes. */
private class FakeGestureHost(private vararg val events: TriggerEvent) : TriggerHost {

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
