package com.example.ottomatic.engine

import com.example.ottomatic.core.model.ConfigKey
import com.example.ottomatic.core.model.NodeId
import com.example.ottomatic.core.model.NodeTypeId
import com.example.ottomatic.core.model.PortName
import com.example.ottomatic.core.trigger.TriggerEvent
import com.example.ottomatic.domain.model.ExecConnection
import com.example.ottomatic.domain.model.Workflow
import com.example.ottomatic.domain.model.WorkflowNode
import com.example.ottomatic.engine.trigger.BatteryDirection
import com.example.ottomatic.engine.trigger.GeofenceTransition
import com.example.ottomatic.engine.trigger.ManualTrigger
import com.example.ottomatic.engine.trigger.ScheduleHandle
import com.example.ottomatic.engine.trigger.TriggerHost
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One trigger's failure is that trigger's failure.
 *
 * A workflow may hold several independent triggers — they share a file because
 * they act on the same thing, not because they depend on each other. Before this,
 * every one of them was a plain child of one job: a source that died took its
 * siblings down with it *and* reached the thread's default handler, i.e. crashed
 * the app. These are the three granularities that must hold.
 */
class WorkflowRunnerTest {

    @Test
    fun `a trigger that throws while arming does not stop the others arming`() = runBlocking {
        val services = RecordingSystemServices()
        val logs = mutableListOf<String>()
        val context = DefaultExecutionContext(services) { logs += it.message }
        // BootTrigger reads busEvents() eagerly, inside activate — so a host that
        // refuses lands in the arming path, not in the collector.
        val host = FakeTriggerHost { error("no bus here") }

        val job = WorkflowRunner(host, context).run(this, twoTriggerWorkflow("arm"))
        repeat(YIELDS) { yield() }
        ManualTrigger.fire(NodeId("arm-manual"))
        repeat(YIELDS) { yield() }
        job.cancelAndJoin()

        assertEquals(listOf("ran"), services.notifications.map { it.second })
        assertTrue(logs.toString(), logs.any { it.contains("Could not arm 'Boot'") })
    }

    @Test
    fun `a trigger source that dies leaves its siblings collecting`() = runBlocking {
        val services = RecordingSystemServices()
        val logs = mutableListOf<String>()
        val context = DefaultExecutionContext(services) { logs += it.message }
        // Arms fine, then the flow fails on collection.
        val host = FakeTriggerHost { flow { error("the bus went away") } }

        val job = WorkflowRunner(host, context).run(this, twoTriggerWorkflow("die"))
        repeat(YIELDS) { yield() }
        ManualTrigger.fire(NodeId("die-manual"))
        repeat(YIELDS) { yield() }
        job.cancelAndJoin()

        // Without the per-flow catch this is empty: the boot collector's failure
        // cancelled the parent, and with it the manual trigger's subscription.
        assertEquals(listOf("ran"), services.notifications.map { it.second })
        assertTrue(logs.toString(), logs.any { it.contains("'Boot' stopped listening") })
    }

    @Test
    fun `cancelling the returned job tears every trigger down`() = runBlocking {
        val services = RecordingSystemServices()
        val context = DefaultExecutionContext(services) {}
        val host = FakeTriggerHost { flow {} }

        val job = WorkflowRunner(host, context).run(this, twoTriggerWorkflow("stop"))
        repeat(YIELDS) { yield() }
        job.cancelAndJoin()
        ManualTrigger.fire(NodeId("stop-manual"))
        repeat(YIELDS) { yield() }

        // The supervisorScope must not have made the job outlive its children, or
        // MacroEngineService's cancel-then-join would no longer disarm anything.
        assertTrue(services.notifications.isEmpty())
    }

    /**
     * A manual trigger that runs, plus a bus-backed one that is about to have a bad
     * day. Ids are prefixed per test because [ManualTrigger] keys its active flows
     * in a process-wide map.
     */
    private fun twoTriggerWorkflow(prefix: String) = Workflow(
        id = "w-runner",
        nodes = listOf(
            WorkflowNode(NodeId("$prefix-manual"), NodeTypeId("trigger.manual"), "Manual", 0f, 0f),
            WorkflowNode(NodeId("$prefix-boot"), NodeTypeId("trigger.boot"), "Boot", 200f, 0f),
            WorkflowNode(
                NodeId("$prefix-n"), NodeTypeId("action.notify"), "Notify", 0f, 100f,
                config = mapOf(ConfigKey("text") to "ran"),
            ),
        ),
        execConnections = listOf(
            ExecConnection(
                "$prefix-c1",
                NodeId("$prefix-manual"), PortName("out"),
                NodeId("$prefix-n"), PortName("in"),
            ),
            ExecConnection(
                "$prefix-c2",
                NodeId("$prefix-boot"), PortName("out"),
                NodeId("$prefix-n"), PortName("in"),
            ),
        ),
    )

    private class FakeTriggerHost(private val bus: () -> Flow<TriggerEvent>) : TriggerHost {
        override fun busEvents(): Flow<TriggerEvent> = bus()

        override fun armGeofence(
            nodeId: NodeId,
            latitude: Double,
            longitude: Double,
            radiusMeters: Float,
            transitions: Set<GeofenceTransition>,
            dwellDelayMs: Int,
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

    private companion object {
        /**
         * `run` subscribes its collectors inside a `launch`, so a fire issued
         * before they are running is dropped — [ManualTrigger]'s flow has no replay.
         */
        const val YIELDS = 8
    }
}
