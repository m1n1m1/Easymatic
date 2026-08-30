package io.github.m1n1m1.easymatic.engine

import io.github.m1n1m1.easymatic.core.model.ConfigKey
import io.github.m1n1m1.easymatic.core.model.NodeId
import io.github.m1n1m1.easymatic.core.model.NodeTypeId
import io.github.m1n1m1.easymatic.core.model.PortName
import io.github.m1n1m1.easymatic.core.trigger.TriggerEvent
import io.github.m1n1m1.easymatic.core.trigger.TriggerSource
import io.github.m1n1m1.easymatic.domain.model.ExecConnection
import io.github.m1n1m1.easymatic.domain.model.Workflow
import io.github.m1n1m1.easymatic.domain.model.WorkflowNode
import io.github.m1n1m1.easymatic.engine.trigger.BatteryDirection
import io.github.m1n1m1.easymatic.engine.trigger.GeofenceArmResult
import io.github.m1n1m1.easymatic.engine.trigger.GeofenceTransition
import io.github.m1n1m1.easymatic.engine.trigger.ScheduleHandle
import io.github.m1n1m1.easymatic.engine.trigger.TriggerHost
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
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
        val context = DefaultExecutionContext(services, notifications = services.notifier) { logs += it.message }
        val good = gate()
        // BootTrigger reads its bus eagerly, inside activate — so a host that
        // refuses lands in the arming path, not in the collector.
        val host = FakeTriggerHost { id -> if (id == BAD) error("no bus here") else good }

        val job = WorkflowRunner(host, context).run(this, twoTriggerWorkflow())
        repeat(YIELDS) { yield() }
        good.tryEmit(bootEvent(GOOD))
        repeat(YIELDS) { yield() }
        job.cancelAndJoin()

        assertEquals(listOf("ran"), services.notifier.titlesAndTexts.map { it.second })
        assertTrue(logs.toString(), logs.any { it.contains("Could not arm 'Boot'") })
    }

    @Test
    fun `a trigger source that dies leaves its siblings collecting`() = runBlocking {
        val services = RecordingSystemServices()
        val logs = mutableListOf<String>()
        val context = DefaultExecutionContext(services, notifications = services.notifier) { logs += it.message }
        val good = gate()
        // Arms fine, then the flow fails on collection.
        val host = FakeTriggerHost { id -> if (id == BAD) flow { error("the bus went away") } else good }

        val job = WorkflowRunner(host, context).run(this, twoTriggerWorkflow())
        repeat(YIELDS) { yield() }
        good.tryEmit(bootEvent(GOOD))
        repeat(YIELDS) { yield() }
        job.cancelAndJoin()

        // Without the per-flow catch this is empty: the failing collector
        // cancelled the parent, and with it the other trigger's subscription.
        assertEquals(listOf("ran"), services.notifier.titlesAndTexts.map { it.second })
        assertTrue(logs.toString(), logs.any { it.contains("'Boot' stopped listening") })
    }

    @Test
    fun `cancelling the returned job tears every trigger down`() = runBlocking {
        val services = RecordingSystemServices()
        val context = DefaultExecutionContext(services, notifications = services.notifier) {}
        val good = gate()
        val host = FakeTriggerHost { id -> if (id == BAD) flow {} else good }

        val job = WorkflowRunner(host, context).run(this, twoTriggerWorkflow())
        repeat(YIELDS) { yield() }
        job.cancelAndJoin()
        good.tryEmit(bootEvent(GOOD))
        repeat(YIELDS) { yield() }

        // The supervisorScope must not have made the job outlive its children, or
        // MacroEngineService's cancel-then-join would no longer disarm anything.
        assertTrue(services.notifier.titlesAndTexts.isEmpty())
    }

    /**
     * A trigger the test can fire on demand, plus one that is about to have a bad
     * day. Both are `trigger.boot` — what matters here is that two sources arm
     * and collect independently, not what either of them watches.
     *
     * The firing half is a hot flow the test emits into rather than a cold one
     * that emits on subscribe, because two of these tests need the event to
     * arrive at a moment they choose: after arming has settled, or after the job
     * has already been cancelled.
     */
    private fun twoTriggerWorkflow() = Workflow(
        id = "w-runner",
        nodes = listOf(
            WorkflowNode(GOOD, NodeTypeId("trigger.boot"), "Tap", 0f, 0f),
            WorkflowNode(BAD, NodeTypeId("trigger.boot"), "Boot", 200f, 0f),
            WorkflowNode(
                NodeId("n"), NodeTypeId("action.notify"), "Notify", 0f, 100f,
                config = mapOf(ConfigKey("text") to "ran"),
            ),
        ),
        execConnections = listOf(
            ExecConnection("c1", GOOD, PortName("out"), NodeId("n"), PortName("in")),
            ExecConnection("c2", BAD, PortName("out"), NodeId("n"), PortName("in")),
        ),
    )

    private fun gate() = MutableSharedFlow<TriggerEvent>(extraBufferCapacity = 1)

    private fun bootEvent(nodeId: NodeId) = TriggerEvent(TriggerSource.BOOT, nodeId)

    /** Dispatches on the node asking, so which flow a trigger gets is not arm order. */
    private class FakeTriggerHost(private val bus: (NodeId) -> Flow<TriggerEvent>) : TriggerHost {
        override fun busEvents(): Flow<TriggerEvent> = flow {}

        override fun busEventsFor(nodeId: NodeId): Flow<TriggerEvent> = bus(nodeId)

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

    private companion object {
        val GOOD = NodeId("good")
        val BAD = NodeId("bad")

        /**
         * `run` subscribes its collectors inside a `launch`, so an event emitted
         * before they are running is dropped — the gate flow has no replay.
         */
        const val YIELDS = 8
    }
}
