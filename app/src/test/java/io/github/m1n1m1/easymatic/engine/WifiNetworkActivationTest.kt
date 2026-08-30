package io.github.m1n1m1.easymatic.engine

import io.github.m1n1m1.easymatic.core.model.NodeId
import io.github.m1n1m1.easymatic.core.model.NodeTypeId
import io.github.m1n1m1.easymatic.core.service.LogLevel
import io.github.m1n1m1.easymatic.core.trigger.TriggerEvent
import io.github.m1n1m1.easymatic.core.trigger.TriggerSource
import io.github.m1n1m1.easymatic.domain.model.WorkflowNode
import io.github.m1n1m1.easymatic.domain.model.items.WifiNetworkEvent
import io.github.m1n1m1.easymatic.engine.trigger.ConnectionEvent
import io.github.m1n1m1.easymatic.engine.trigger.ScheduleHandle
import io.github.m1n1m1.easymatic.engine.trigger.TriggerHost
import io.github.m1n1m1.easymatic.engine.trigger.WifiNetworkConfig
import io.github.m1n1m1.easymatic.engine.trigger.WifiNetworkTrigger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What `trigger.wifi_network` lets through, and what it says when it cannot tell.
 *
 * [WifiFakeHost] serves a finite bus, so collecting the trigger's flow completes
 * once the scripted events have been delivered rather than waiting forever.
 */
class WifiNetworkActivationTest {

    private val node = WorkflowNode(NodeId("n1"), NodeTypeId("trigger.wifi_network"), "Wi-Fi", 0f, 0f)

    private val joinedOffice = busEvent("connected", "Office-5G")
    private val leftOffice = busEvent("disconnected", "Office-5G")
    private val joinedCafe = busEvent("connected", "Cafe")

    @Test
    fun `an unconfigured trigger runs on every network and both directions`() = runBlocking {
        val emitted = collect(WifiNetworkConfig(), joinedOffice, leftOffice, joinedCafe)

        assertEquals(
            listOf("connected" to "Office-5G", "disconnected" to "Office-5G", "connected" to "Cafe"),
            emitted.map { it.event to it.ssid },
        )
    }

    @Test
    fun `a configured network filters out every other one`() = runBlocking {
        val emitted = collect(WifiNetworkConfig(ssid = "Office-5G"), joinedOffice, joinedCafe)

        assertEquals(listOf("Office-5G"), emitted.map { it.ssid })
    }

    @Test
    fun `an event filter keeps only that direction`() = runBlocking {
        val emitted = collect(
            WifiNetworkConfig(event = ConnectionEvent.DISCONNECTED),
            joinedOffice,
            leftOffice,
            joinedCafe,
        )

        assertEquals(listOf("disconnected" to "Office-5G"), emitted.map { it.event to it.ssid })
    }

    /** The two filters narrow together rather than either one winning. */
    @Test
    fun `both filters apply at once`() = runBlocking {
        val emitted = collect(
            WifiNetworkConfig(ssid = "Office-5G", event = ConnectionEvent.CONNECTED),
            joinedOffice,
            leftOffice,
            joinedCafe,
        )

        assertEquals(listOf("connected" to "Office-5G"), emitted.map { it.event to it.ssid })
    }

    /**
     * The platform's placeholder must not become a network called "unknown ssid" — it
     * would match nothing anybody configured and compare equal across two genuinely
     * different networks.
     */
    @Test
    fun `an unnameable network still fires when no filter is set, with a blank name`() = runBlocking {
        val emitted = collect(WifiNetworkConfig(), busEvent("connected", "<unknown ssid>"))

        assertEquals(listOf(""), emitted.map { it.ssid })
    }

    /**
     * The failure this warning exists for looks exactly like a macro that is simply
     * waiting: armed, receiving events, and unable to match any of them because
     * nothing is allowed to name them.
     */
    @Test
    fun `a filter that can never match says so, once`() = runBlocking {
        val host = WifiFakeHost(busEvent("connected", "<unknown ssid>"), busEvent("disconnected", ""))

        val emitted = WifiNetworkTrigger()
            .activate(WifiNetworkConfig(ssid = "Office-5G"), node, host)
            .toList()

        assertTrue("nothing can match, so nothing should fire", emitted.isEmpty())
        val warnings = host.reported.filter { it.level == LogLevel.WARN }
        assertEquals("both events are unnameable, but one line is enough", 1, warnings.size)
        assertTrue("the fix has to be named", warnings.single().message.contains("Location"))
    }

    /** Nothing to warn about when the trigger runs on any network. */
    @Test
    fun `an unnameable network is not a warning when no filter is set`() = runBlocking {
        val host = WifiFakeHost(busEvent("connected", "<unknown ssid>"))

        WifiNetworkTrigger().activate(WifiNetworkConfig(), node, host).toList()

        assertTrue(host.reported.none { it.level == LogLevel.WARN })
    }

    /** An armed macro should say what it is armed for, not only show it on the card. */
    @Test
    fun `activation reports what is being watched`() = runBlocking {
        val anyNetwork = WifiFakeHost()
        WifiNetworkTrigger().activate(WifiNetworkConfig(), node, anyNetwork).toList()
        assertTrue(anyNetwork.reported.single().message.contains("any Wi-Fi network"))

        val oneNetwork = WifiFakeHost()
        WifiNetworkTrigger()
            .activate(WifiNetworkConfig(ssid = "Office-5G", event = ConnectionEvent.CONNECTED), node, oneNetwork)
            .toList()
        val message = oneNetwork.reported.single().message
        assertTrue(message, message.contains("'Office-5G'"))
        assertTrue(message, message.contains("connect"))
    }

    private suspend fun collect(
        config: WifiNetworkConfig,
        vararg events: TriggerEvent,
    ): List<WifiNetworkEvent> =
        WifiNetworkTrigger()
            .activate(config, node, WifiFakeHost(*events))
            .toList()
            .map { it.value }

    private fun busEvent(event: String, ssid: String) = TriggerEvent(
        source = TriggerSource.CONNECTIVITY,
        triggerNodeId = NodeId.BROADCAST,
        payload = mapOf(
            "triggerType" to WifiNetworkTrigger.TRIGGER_TYPE,
            "event" to event,
            "detail" to ssid,
            "timestamp" to "1000",
        ),
    )
}

/**
 * One line the trigger wrote to its workflow's console.
 *
 * Named apart from `GeofenceActivationTest`'s identical helper because file-private
 * top-level declarations still share a package namespace in Kotlin, so two files in
 * `engine` cannot both call theirs `Reported`.
 */
private data class WifiReported(val message: String, val level: LogLevel)

/** Serves a finite scripted bus, so a collection terminates. */
private class WifiFakeHost(private val events: List<TriggerEvent>) : TriggerHost {

    constructor(vararg events: TriggerEvent) : this(events.toList())

    val reported = mutableListOf<WifiReported>()

    override fun busEvents(): Flow<TriggerEvent> = events.asFlow()

    override fun report(node: WorkflowNode, message: String, level: LogLevel) {
        reported += WifiReported(message, level)
    }

    override fun armSchedule(nodeId: NodeId, intervalMinutes: Long) = ScheduleHandle { }

    override fun armAlarm(nodeId: NodeId, atEpochMs: Long) = ScheduleHandle { }

    override fun armBatteryLevelPoll(
        nodeId: NodeId,
        intervalMinutes: Long,
        direction: io.github.m1n1m1.easymatic.engine.trigger.BatteryDirection,
        threshold: Int,
    ) = ScheduleHandle { }

    override fun armGeofence(
        nodeId: NodeId,
        latitude: Double,
        longitude: Double,
        radiusMeters: Float,
        transitions: Set<io.github.m1n1m1.easymatic.engine.trigger.GeofenceTransition>,
        dwellDelayMs: Int,
        onResult: (io.github.m1n1m1.easymatic.engine.trigger.GeofenceArmResult) -> Unit,
    ) = ScheduleHandle { }
}
