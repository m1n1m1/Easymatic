package com.example.ottomatic.engine

import com.example.ottomatic.core.model.NodeId
import com.example.ottomatic.core.model.NodeTypeId
import com.example.ottomatic.core.trigger.TriggerEvent
import com.example.ottomatic.domain.model.GeofencePlace
import com.example.ottomatic.domain.model.WorkflowNode
import com.example.ottomatic.engine.trigger.BatteryDirection
import com.example.ottomatic.engine.trigger.GeofenceConfig
import com.example.ottomatic.engine.trigger.GeofenceTransition
import com.example.ottomatic.engine.trigger.GeofenceTrigger
import com.example.ottomatic.engine.trigger.ScheduleHandle
import com.example.ottomatic.engine.trigger.TriggerHost
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers what the geofence trigger does at *activation*, which is where the
 * place indirection lives: an id that resolves gets armed at the place's centre
 * and radius, and one that does not resolve arms nothing at all.
 *
 * [FakeTriggerHost] serves an empty bus, so collecting the trigger's flow
 * completes as soon as it has armed rather than waiting for events forever.
 */
class GeofenceActivationTest {

    private val node = WorkflowNode(NodeId("n1"), NodeTypeId("trigger.geofence"), "Geofence", 0f, 0f)

    private val home = GeofencePlace(
        id = "place-1",
        name = "Home",
        latitude = 48.2082,
        longitude = 16.3738,
        radiusMeters = 250f,
    )

    @Test
    fun `arms the platform geofence at the referenced place`() = runBlocking {
        val host = FakeTriggerHost(places = mapOf(home.id to home))

        GeofenceTrigger()
            .activate(GeofenceConfig(placeId = home.id, onExit = true), node, host)
            .toList()

        val armed = requireNotNull(host.armed) { "expected the trigger to arm a geofence" }
        assertEquals(NodeId("n1"), armed.nodeId)
        assertEquals(home.latitude, armed.latitude, 0.0)
        assertEquals(home.longitude, armed.longitude, 0.0)
        // The radius comes from the place, not the node.
        assertEquals(home.radiusMeters, armed.radiusMeters, 0f)
        assertEquals(setOf(GeofenceTransition.ENTER, GeofenceTransition.EXIT), armed.transitions)
    }

    @Test
    fun `arms nothing when no place has been chosen`() = runBlocking {
        val host = FakeTriggerHost(places = mapOf(home.id to home))

        val emissions = GeofenceTrigger().activate(GeofenceConfig(placeId = ""), node, host).toList()

        assertTrue(emissions.isEmpty())
        assertNull(host.armed)
    }

    @Test
    fun `arms nothing when the referenced place has been deleted`() = runBlocking {
        // The library no longer holds the id the node still points at.
        val host = FakeTriggerHost(places = emptyMap())

        val emissions = GeofenceTrigger().activate(GeofenceConfig(placeId = home.id), node, host).toList()

        assertTrue(emissions.isEmpty())
        assertNull(host.armed)
    }

    @Test
    fun `cancelling the flow removes the geofence`() = runBlocking {
        val host = FakeTriggerHost(places = mapOf(home.id to home))

        GeofenceTrigger().activate(GeofenceConfig(placeId = home.id), node, host).toList()

        assertTrue("the handle must be cancelled when collection ends", host.cancelled)
    }
}

private data class ArmedGeofence(
    val nodeId: NodeId,
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Float,
    val transitions: Set<GeofenceTransition>,
    val dwellDelayMs: Int,
)

/** Records what was armed instead of touching Play Services. */
private class FakeTriggerHost(private val places: Map<String, GeofencePlace>) : TriggerHost {

    var armed: ArmedGeofence? = null
        private set

    var cancelled: Boolean = false
        private set

    override fun busEvents(): Flow<TriggerEvent> = emptyFlow()

    override fun geofencePlace(id: String): GeofencePlace? = places[id]

    override fun armGeofence(
        nodeId: NodeId,
        latitude: Double,
        longitude: Double,
        radiusMeters: Float,
        transitions: Set<GeofenceTransition>,
        dwellDelayMs: Int,
    ): ScheduleHandle {
        armed = ArmedGeofence(nodeId, latitude, longitude, radiusMeters, transitions, dwellDelayMs)
        return ScheduleHandle { cancelled = true }
    }

    override fun armSchedule(nodeId: NodeId, intervalMinutes: Long) = ScheduleHandle { }

    override fun armAlarm(nodeId: NodeId, atEpochMs: Long) = ScheduleHandle { }

    override fun armBatteryLevelPoll(
        nodeId: NodeId,
        intervalMinutes: Long,
        direction: BatteryDirection,
        threshold: Int,
    ) = ScheduleHandle { }
}
