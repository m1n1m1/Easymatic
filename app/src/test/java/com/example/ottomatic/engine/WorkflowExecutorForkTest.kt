package com.example.ottomatic.engine

import com.example.ottomatic.core.model.ConfigKey
import com.example.ottomatic.core.model.NodeId
import com.example.ottomatic.core.model.NodeTypeId
import com.example.ottomatic.core.model.PortName
import com.example.ottomatic.core.service.LogEntry
import com.example.ottomatic.core.service.LogLevel
import com.example.ottomatic.core.service.Waits
import com.example.ottomatic.domain.model.DataConnection
import com.example.ottomatic.domain.model.ExecConnection
import com.example.ottomatic.domain.model.ExecPorts
import com.example.ottomatic.domain.model.Workflow
import com.example.ottomatic.domain.model.WorkflowNode
import com.example.ottomatic.domain.registry.LOOP_INDEX_OUT
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections

/**
 * What a fork actually does to a walk — `action.wait_until` is the subject, but
 * every assertion here is about [ForkAction] and [WorkflowExecutor.runFork].
 *
 * The wait is held open by [GatedWaits] rather than by a clock, so every test
 * gets to say exactly when the moment arrives and then assert on both sides of
 * it. The deferred scope is [Dispatchers.Unconfined], which starts the branch
 * eagerly and resumes it on the thread that opens the gate — so "before" and
 * "after" mean what they say without any waiting for a scheduler.
 */
class WorkflowExecutorForkTest {

    private val logs = Collections.synchronizedList(mutableListOf<LogEntry>())
    private val waits = GatedWaits()
    private val context = DefaultExecutionContext(RecordingSystemServices(), waits = waits) { logs += it }
    private val deferredScope = CoroutineScope(Job() + Dispatchers.Unconfined)

    @After
    fun tearDown() {
        // Also releases the pending-wait slots the cancelled branches were holding.
        deferredScope.cancel()
    }

    @Test
    fun `the immediate branch runs while the deferred one is still waiting`() = runBlocking {
        run(forkWorkflow())

        assertTrue(logs.messages().toString(),"carried on" in logs.messages())
        assertTrue("the deferred branch ran early", "the moment came" !in logs.messages())
    }

    @Test
    fun `the deferred branch runs when the moment arrives`() = runBlocking {
        run(forkWorkflow())
        waits.arrive()

        assertTrue(logs.messages().toString(),"the moment came" in logs.messages())
    }

    /**
     * The whole point of the node: a run *finishes* without waiting for its own
     * wait. If this regressed, a trigger would stop collecting for the duration
     * and `trigger.macro_finished` would fire hours late — neither of which is
     * visible from inside the executor.
     */
    @Test
    fun `the run returns before the wait is over`() = runBlocking {
        val returned = runFromTrigger(
            context,
            forkWorkflow(),
            forkWorkflow().node(NodeId("n1"))!!,
            deferredScope = deferredScope,
        )

        assertTrue(returned)
        assertTrue("the deferred branch ran early", "the moment came" !in logs.messages())
        waits.arrive()
        assertTrue(logs.messages().toString(),"the moment came" in logs.messages())
    }

    /**
     * A `Stop Macro` on the branch that carries on has to stop the wait as well.
     * A macro that is visibly still counting down and cannot be stopped is a
     * "Stop Macro" that does not stop the macro.
     */
    @Test
    fun `stopping the immediate branch cancels a wait that has not fired`() = runBlocking {
        run(stoppingForkWorkflow())
        waits.arrive()

        assertTrue("the cancelled branch ran anyway", "the moment came" !in logs.messages())
    }

    /**
     * The snapshot, tested where it is actually load-bearing. Each pass forks, and
     * every deferred branch fires after the loop has moved on — so only a copy
     * taken at its own fork still carries that pass's index.
     */
    @Test
    fun `a fork in a loop body gives every pass its own deferred branch`() = runBlocking {
        run(loopedForkWorkflow())
        waits.arrive()

        // INFO only: the node's own line, not the executor's DEBUG trace around it.
        val logged = logs.filter { it.source?.nodeId == "say" && it.level == LogLevel.INFO }
        assertEquals(listOf("0", "1", "2"), logged.map { it.message })
    }

    /** A moment already gone is reported, and nothing is left waiting for it. */
    @Test
    fun `a moment in the past never resumes, and says so`() = runBlocking {
        run(forkWorkflow(mode = "DATE_TIME"))
        waits.arrive()

        assertTrue(logs.messages().toString(),"carried on" in logs.messages())
        assertTrue("it resumed anyway", "the moment came" !in logs.messages())
        val warned = logs.single { it.level == LogLevel.WARN && it.message.contains("will not resume") }
        assertEquals("w", warned.source?.nodeId)
    }

    /**
     * A fork's deferred branch wired back into the fork is a cycle like any other:
     * the validator quarantines the closing wire, so the branch fires once and
     * stops rather than re-entering. The executor's own `onPath` copy is the
     * backstop underneath this — it is what a hand-edited file would meet — and
     * what matters either way is that this terminates.
     */
    @Test
    fun `the deferred branch cannot loop back into the fork`() = runBlocking {
        run(cyclicForkWorkflow())
        waits.arrive()

        assertTrue(
            logs.messages().toString(),
            logs.any { it.level == LogLevel.ERROR && it.message.contains("that wire has a problem") },
        )
        assertEquals(1, logs.count { it.message.startsWith("Waiting until") })
    }

    /**
     * The cap is announced, never silent — a fork that quietly stopped forking
     * would read exactly like a moment that has not come yet.
     */
    @Test
    fun `past the cap the immediate branch still runs and the wait is refused`() = runBlocking {
        run(loopedForkWorkflow(times = MAX_PENDING_WAITS + 2))

        val refused = logs.filter { it.level == LogLevel.WARN && it.message.contains("already pending") }
        assertEquals(2, refused.size)
        // Two waits refused, but all of the passes carried on.
        assertEquals(MAX_PENDING_WAITS + 2, logs.count { it.message == "carried on" })
    }

    /**
     * With no scope to detach to, the branches run one after the other — but in
     * the *same order*, so an assertion written against one mode holds in the
     * other. Without this the inline path could quietly invert into "wait, then
     * carry on", which is `action.delay` wearing the wrong node's ports.
     */
    @Test
    fun `with no scope to detach to, the immediate branch still runs first`() = runBlocking {
        // Open before the run, since nothing else will get the chance to.
        waits.arrive()
        val workflow = forkWorkflow()
        WorkflowExecutor(context).executeFrom(workflow, workflow.node(NodeId("n1"))!!, PULSE_ONLY)

        // The two nodes' own INFO lines, in the order they were written.
        val said = logs
            .filter { it.level == LogLevel.INFO && it.source?.nodeId in setOf("now", "later") }
            .map { it.message }
        assertEquals(listOf("carried on", "the moment came"), said)
    }

    private suspend fun run(workflow: Workflow) {
        WorkflowExecutor(context, deferredScope)
            .executeFrom(workflow, workflow.node(NodeId("n1"))!!, PULSE_ONLY)
    }

    private fun List<LogEntry>.messages(): List<String> = map { it.message }

    /** Manual → Wait Until, with a Log on each of its two branches. */
    private fun forkWorkflow(mode: String = "DURATION") = Workflow(
        id = WORKFLOW,
        nodes = listOf(
            trigger(),
            wait("w", mode),
            log("now", "carried on"),
            log("later", "the moment came"),
        ),
        execConnections = listOf(
            exec("c0", "n1", ExecPorts.OUT, "w"),
            exec("c1", "w", ExecPorts.OUT, "now"),
            exec("c2", "w", ExecPorts.RESUMED, "later"),
        ),
    )

    /** The same, with the immediate branch halting before the moment arrives. */
    private fun stoppingForkWorkflow() = Workflow(
        id = WORKFLOW,
        nodes = listOf(
            trigger(),
            wait("w"),
            WorkflowNode(NodeId("stop"), NodeTypeId("action.stop"), "Stop", 0f, 100f),
            log("later", "the moment came"),
        ),
        execConnections = listOf(
            exec("c0", "n1", ExecPorts.OUT, "w"),
            exec("c1", "w", ExecPorts.OUT, "stop"),
            exec("c2", "w", ExecPorts.RESUMED, "later"),
        ),
    )

    /**
     * Repeat → Wait Until in the body, with the pass index converted to text and
     * logged on the *deferred* side.
     */
    private fun loopedForkWorkflow(times: Int = 3) = Workflow(
        id = WORKFLOW,
        nodes = listOf(
            trigger(),
            WorkflowNode(
                NodeId("rep"), NodeTypeId("action.repeat"), "Repeat", 0f, 50f,
                config = mapOf(ConfigKey("times") to times.toString()),
            ),
            wait("w"),
            log("now", "carried on"),
            WorkflowNode(
                NodeId("cvt"), NodeTypeId("transform.convert"), "Convert", 0f, 120f,
                config = mapOf(ConfigKey("to") to "TEXT"),
            ),
            WorkflowNode(NodeId("say"), NodeTypeId("action.log"), "Say", 0f, 150f),
        ),
        execConnections = listOf(
            exec("c0", "n1", ExecPorts.OUT, "rep"),
            exec("c1", "rep", ExecPorts.BODY, "w"),
            exec("c2", "w", ExecPorts.OUT, "now"),
            exec("c3", "w", ExecPorts.RESUMED, "say"),
        ),
        dataConnections = listOf(
            DataConnection("d1", NodeId("rep"), LOOP_INDEX_OUT, NodeId("cvt"), PortName("in")),
            DataConnection("d2", NodeId("cvt"), PortName("value"), NodeId("say"), PortName("message")),
        ),
    )

    /** The deferred branch wired back into the fork it came from. */
    private fun cyclicForkWorkflow() = Workflow(
        id = WORKFLOW,
        nodes = listOf(trigger(), wait("w")),
        execConnections = listOf(
            exec("c0", "n1", ExecPorts.OUT, "w"),
            exec("c1", "w", ExecPorts.RESUMED, "w"),
        ),
    )

    private fun wait(id: String, mode: String = "DURATION") = WorkflowNode(
        NodeId(id), NodeTypeId("action.wait_until"), "Wait Until", 0f, 80f,
        config = mapOf(
            ConfigKey("mode") to mode,
            ConfigKey("duration") to "1",
            ConfigKey("unit") to "HOURS",
            // Long past, so DATE_TIME mode has nothing left to wait for.
            ConfigKey("until") to "2000-01-01T00:00:00Z",
        ),
    )

    private fun log(id: String, message: String) = WorkflowNode(
        NodeId(id), NodeTypeId("action.log"), "Log $id", 0f, 150f,
        config = mapOf(ConfigKey("message") to message),
    )

    private fun trigger() = WorkflowNode(NodeId("n1"), NodeTypeId("trigger.manual"), "Manual", 0f, 0f)

    private fun exec(id: String, from: String, port: PortName, to: String) =
        ExecConnection(id, NodeId(from), port, NodeId(to), ExecPorts.IN)

    /**
     * A [Waits] that never ends on its own: every waiter parks until [arrive] is
     * called, which is what lets a test assert on the state of the graph *during*
     * a wait rather than only after it.
     */
    private class GatedWaits : Waits {
        private val gate = CompletableDeferred<Unit>()

        override suspend fun <T> awaitUntil(atEpochMs: Long, thenDo: suspend () -> T): T {
            gate.await()
            return thenDo()
        }

        /** Releases every wait at once. */
        fun arrive() {
            gate.complete(Unit)
        }
    }

    private companion object {
        const val WORKFLOW = "w-fork"
    }
}
