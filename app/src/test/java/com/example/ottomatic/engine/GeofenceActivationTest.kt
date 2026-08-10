package com.example.ottomatic.engine

import com.example.ottomatic.core.model.NodeId
import com.example.ottomatic.core.model.NodeTypeId
import com.example.ottomatic.core.trigger.TriggerEvent
import com.example.ottomatic.core.trigger.TriggerSource
import com.example.ottomatic.domain.model.GeofencePlace
import com.example.ottomatic.core.service.LogLevel
import com.example.ottomatic.domain.model.WorkflowNode
import com.example.ottomatic.engine.trigger.BatteryDirection
import com.example.ottomatic.engine.trigger.GeofenceArmResult
import com.example.ottomatic.engine.trigger.GeofenceConfig
import com.example.ottomatic.engine.trigger.GeofenceTransition
import com.example.ottomatic.engine.trigger.GeofenceTrigger
import com.example.ottomatic.engine.trigger.ScheduleHandle
import com.example.ottomatic.engine.trigger.TriggerHost
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
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

    /**
     * The away half has no platform transition behind it, so the fence has to
     * report the two that drive it — an exit starts the countdown, an enter
     * cancels it — even though this node publishes neither.
     */
    @Test
    fun `arming only the away countdown still watches enter and exit`() = runBlocking {
        val host = FakeTriggerHost(places = mapOf(home.id to home))

        GeofenceTrigger()
            .activate(GeofenceConfig(placeId = home.id, onEnter = false, onAway = true), node, host)
            .toList()

        val armed = requireNotNull(host.armed)
        assertEquals(setOf(GeofenceTransition.ENTER, GeofenceTransition.EXIT), armed.transitions)
    }

    @Test
    fun `an exit starts the away countdown with the configured delay`() = runBlocking {
        val host = FakeTriggerHost(places = mapOf(home.id to home), bus = flowOf(geofence("exit")))

        GeofenceTrigger()
            .activate(GeofenceConfig(placeId = home.id, onEnter = false, onAway = true, awayMinutes = 45), node, host)
            .toList()

        assertEquals(listOf(45 * 60_000L), host.awayArmed)
        assertTrue(host.awayCancelled.isEmpty())
    }

    /**
     * And the exit that started it is not itself an event: a macro that only
     * asked about staying away must not also run the moment the door closes.
     */
    @Test
    fun `an exit that drives the countdown is not emitted`() = runBlocking {
        val host = FakeTriggerHost(places = mapOf(home.id to home), bus = flowOf(geofence("exit")))

        val emissions = GeofenceTrigger()
            .activate(GeofenceConfig(placeId = home.id, onEnter = false, onAway = true), node, host)
            .toList()

        assertTrue(emissions.isEmpty())
    }

    @Test
    fun `coming back cancels the away countdown`() = runBlocking {
        val host = FakeTriggerHost(
            places = mapOf(home.id to home),
            bus = flowOf(geofence("exit"), geofence("enter")),
        )

        GeofenceTrigger()
            .activate(GeofenceConfig(placeId = home.id, onEnter = false, onAway = true), node, host)
            .toList()

        assertEquals(listOf(NodeId("n1")), host.awayCancelled)
    }

    @Test
    fun `a node with no away countdown never touches one`() = runBlocking {
        val host = FakeTriggerHost(
            places = mapOf(home.id to home),
            bus = flowOf(geofence("exit"), geofence("enter")),
        )

        GeofenceTrigger()
            .activate(GeofenceConfig(placeId = home.id, onExit = true), node, host)
            .toList()

        assertTrue(host.awayArmed.isEmpty())
        assertTrue(host.awayCancelled.isEmpty())
    }

    /**
     * The away alarm reports no location — it knows only which node it belongs
     * to — so the place's own coordinates stand in.
     */
    @Test
    fun `the away event reaches the port with the place's coordinates`() = runBlocking {
        val host = FakeTriggerHost(places = mapOf(home.id to home), bus = flowOf(geofence("away")))

        val emissions = GeofenceTrigger()
            .activate(GeofenceConfig(placeId = home.id, onEnter = false, onAway = true), node, host)
            .toList()

        val event = emissions.single().value
        assertEquals("away", event.transition)
        assertEquals(home.latitude, event.latitude, 0.0)
        assertEquals(home.longitude, event.longitude, 0.0)
    }

    @Test
    fun `an away announcement names the away switch rather than the fence`() = runBlocking {
        val host = FakeTriggerHost(places = mapOf(home.id to home))

        GeofenceTrigger()
            .activate(GeofenceConfig(placeId = home.id, onEnter = false, onAway = true), node, host)
            .toList()

        val line = host.reported.single { it.level == LogLevel.INFO }
        assertTrue("the enter/exit the platform was told about are not the user's macro", line.message.endsWith("away"))
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

    /**
     * And leaves the away countdown alone, which is the one piece of this that
     * looks like a leak and is not. The exit that arms the countdown also asks
     * the engine to re-arm, so a countdown torn down with the flow would be
     * cancelled seconds after it started, every single time.
     */
    @Test
    fun `cancelling the flow leaves the away countdown running`() = runBlocking {
        val host = FakeTriggerHost(places = mapOf(home.id to home), bus = flowOf(geofence("exit")))

        GeofenceTrigger()
            .activate(GeofenceConfig(placeId = home.id, onEnter = false, onAway = true), node, host)
            .toList()

        assertTrue(host.cancelled)
        assertTrue("the countdown outlives the arm on purpose", host.awayCancelled.isEmpty())
    }

    private fun geofence(event: String) = TriggerEvent(
        source = TriggerSource.GEOFENCE,
        triggerNodeId = node.id,
        payload = mapOf("event" to event),
    )

    /**
     * The four lines below are the whole point of the arm-time console: before
     * them, a macro watching nowhere and a macro watching correctly and seeing
     * nothing were indistinguishable from the editor.
     */
    @Test
    fun `a trigger with no place says so`() = runBlocking {
        val host = FakeTriggerHost(places = mapOf(home.id to home))

        GeofenceTrigger().activate(GeofenceConfig(placeId = ""), node, host).toList()

        val warning = host.reported.single { it.level == LogLevel.WARN }
        assertEquals(node.id, warning.nodeId)
        assertTrue(warning.message.contains("No place chosen"))
    }

    @Test
    fun `a trigger whose place was deleted says so`() = runBlocking {
        val host = FakeTriggerHost(places = emptyMap())

        GeofenceTrigger().activate(GeofenceConfig(placeId = home.id), node, host).toList()

        val warning = host.reported.single { it.level == LogLevel.WARN }
        assertTrue(warning.message.contains("no longer exists"))
    }

    @Test
    fun `a registered fence is announced with its place and radius`() = runBlocking {
        val host = FakeTriggerHost(places = mapOf(home.id to home))

        GeofenceTrigger().activate(GeofenceConfig(placeId = home.id), node, host).toList()

        val line = host.reported.single { it.level == LogLevel.INFO }
        assertTrue(line.message.contains("Home"))
        assertTrue(line.message.contains("250 m"))
    }

    /**
     * The line that would have answered the original question. A refusal used to
     * be one `Log.w` in Logcat, under a macro whose switch still read "on".
     *
     * The reason is passed through verbatim rather than decorated: the host has
     * already turned the Play Services code into something a person can act on,
     * and a second generic sentence appended here would bury it.
     */
    @Test
    fun `a refused fence reports the reason it was given`() = runBlocking {
        val host = FakeTriggerHost(
            places = mapOf(home.id to home),
            armResult = GeofenceArmResult.Refused(1004, "Set Location to \"Allow all the time\"."),
        )

        GeofenceTrigger().activate(GeofenceConfig(placeId = home.id), node, host).toList()

        val error = host.reported.single { it.level == LogLevel.ERROR }
        assertTrue("the place has to be named", error.message.contains("Home"))
        assertTrue("the reason must survive intact", error.message.contains("Allow all the time"))
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

/** One line the trigger wrote to its workflow's console. */
private data class Reported(val nodeId: NodeId, val message: String, val level: LogLevel)

/**
 * Records what was armed instead of touching Play Services.
 *
 * [armResult] stands in for the platform's asynchronous verdict — the real one
 * arrives on a GMS callback well after `armGeofence` has returned, so a test can
 * only get at it by choosing what to hand back.
 */
private class FakeTriggerHost(
    private val places: Map<String, GeofencePlace>,
    private val armResult: GeofenceArmResult = GeofenceArmResult.Registered,
    /**
     * The transitions the platform "reports". Finite, so collecting the
     * trigger's flow completes rather than waiting for events forever.
     */
    private val bus: Flow<TriggerEvent> = emptyFlow(),
) : TriggerHost {

    var armed: ArmedGeofence? = null
        private set

    var cancelled: Boolean = false
        private set

    /** Away-countdown delays asked for, and the nodes told to stop counting. */
    val awayArmed = mutableListOf<Long>()
    val awayCancelled = mutableListOf<NodeId>()

    val reported = mutableListOf<Reported>()

    override fun busEvents(): Flow<TriggerEvent> = bus

    override fun geofencePlace(id: String): GeofencePlace? = places[id]

    override fun report(node: WorkflowNode, message: String, level: LogLevel) {
        reported += Reported(node.id, message, level)
    }

    override fun armGeofence(
        nodeId: NodeId,
        latitude: Double,
        longitude: Double,
        radiusMeters: Float,
        transitions: Set<GeofenceTransition>,
        dwellDelayMs: Int,
        onResult: (GeofenceArmResult) -> Unit,
    ): ScheduleHandle {
        armed = ArmedGeofence(nodeId, latitude, longitude, radiusMeters, transitions, dwellDelayMs)
        onResult(armResult)
        return ScheduleHandle { cancelled = true }
    }

    override fun armGeofenceAway(nodeId: NodeId, awayDelayMs: Long) {
        awayArmed += awayDelayMs
    }

    override fun cancelGeofenceAway(nodeId: NodeId) {
        awayCancelled += nodeId
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
