package com.example.ottomatic.engine

import com.example.ottomatic.core.model.ConfigKey
import com.example.ottomatic.core.model.NodeId
import com.example.ottomatic.core.model.NodeTypeId
import com.example.ottomatic.core.model.PortName
import com.example.ottomatic.domain.model.DataConnection
import com.example.ottomatic.domain.model.ExecConnection
import com.example.ottomatic.domain.model.ExecPorts
import com.example.ottomatic.domain.model.Workflow
import com.example.ottomatic.domain.model.WorkflowNode
import com.example.ottomatic.domain.registry.CONVERT_IN
import com.example.ottomatic.domain.registry.CONVERT_TO_KEY
import com.example.ottomatic.domain.registry.FOR_EACH_ITEM_OUT
import com.example.ottomatic.domain.registry.FOR_EACH_LIST_IN
import com.example.ottomatic.domain.registry.LOOP_INDEX_OUT
import com.example.ottomatic.domain.registry.TRANSFORM_OUT
import com.example.ottomatic.engine.trigger.TriggerOutput
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Iteration, end to end through the real [WorkflowExecutor].
 *
 * The shape under test is the whole reason loops cost the engine nothing: `body`
 * and `completed` are ordinary forward exec ports, so what has to be proved is that
 * the executor pulses the first once per element with the right data on the loop
 * node's own ports, and the second exactly once afterwards.
 *
 * The list comes from a real `transform.split_text` node rather than a fabricated
 * trigger output, so every graph here is one the editor would accept — including
 * the type checks, which is what makes the `index` cases go through a
 * `transform.convert` exactly as the autocast would place one.
 */
class WorkflowExecutorLoopTest {

    private val services = RecordingSystemServices()
    private val logs = mutableListOf<String>()
    private val context = DefaultExecutionContext(services, notifications = services.notifier) { logs += it.message }
    private val executor = WorkflowExecutor(context)

    private val trigger = NodeId("trigger")
    private val source = NodeId("source")
    private val loop = NodeId("loop")
    private val convert = NodeId("convert")
    private val body = NodeId("body")
    private val after = NodeId("after")

    /**
     * `trigger.manual` → `action.for_each` → body notify, completed notify, with
     * the list supplied by `transform.split_text` over [items] (one per line).
     *
     * [from] is the loop output wired into the body, through a `transform.convert`
     * so that a numeric `index` reaches a text field the same visible way it would
     * on the canvas. The two notifications are told apart by title, so one
     * recording list answers both "how many passes?" and "did completed fire?".
     */
    private fun workflowOver(items: List<String>, from: PortName = FOR_EACH_ITEM_OUT): Workflow = Workflow(
        nodes = listOf(
            WorkflowNode(trigger, NodeTypeId("trigger.manual"), "Manual", 0f, 0f),
            WorkflowNode(
                source, NodeTypeId("transform.split_text"), "Items", 0f, 50f,
                config = mapOf(ConfigKey("text") to items.joinToString("\n")),
            ),
            WorkflowNode(loop, NodeTypeId("action.for_each"), "For each", 0f, 100f),
            WorkflowNode(
                convert, NodeTypeId("transform.convert"), "Convert", 0f, 150f,
                config = mapOf(CONVERT_TO_KEY to "TEXT"),
            ),
            WorkflowNode(
                body, NodeTypeId("action.notify"), "Body", 0f, 200f,
                config = mapOf(ConfigKey("title") to "body"),
            ),
            WorkflowNode(
                after, NodeTypeId("action.notify"), "After", 0f, 300f,
                config = mapOf(ConfigKey("title") to "done", ConfigKey("text") to "finished"),
            ),
        ),
        execConnections = listOf(
            ExecConnection("e1", trigger, ExecPorts.OUT, loop, ExecPorts.IN),
            ExecConnection("e2", loop, ExecPorts.BODY, body, ExecPorts.IN),
            ExecConnection("e3", loop, ExecPorts.COMPLETED, after, ExecPorts.IN),
        ),
        dataConnections = listOf(
            DataConnection("d1", source, TRANSFORM_OUT, loop, FOR_EACH_LIST_IN),
            DataConnection("d2", loop, from, convert, CONVERT_IN),
            DataConnection("d3", convert, TRANSFORM_OUT, body, PortName("text")),
        ),
    )

    private suspend fun run(workflow: Workflow) {
        executor.executeFrom(workflow, workflow.node(trigger)!!, TriggerOutput(emptyMap()))
        // A blocked node is skipped in near-silence, so a graph that failed
        // validation would otherwise present as "the loop ran zero times".
        assertTrue(logs.toString(), logs.none { it.contains("problem") })
    }

    private fun bodyTexts() = services.notifier.titlesAndTexts.filter { it.first == "body" }.map { it.second }

    private fun completedCount() = services.notifier.titlesAndTexts.count { it.first == "done" }

    @Test
    fun `the body runs once per element, carrying that element`() = runBlocking {
        run(workflowOver(listOf("a", "b", "c")))
        assertEquals(listOf("a", "b", "c"), bodyTexts())
    }

    @Test
    fun `completed fires exactly once, after the last pass`() = runBlocking {
        run(workflowOver(listOf("a", "b")))
        assertEquals(1, completedCount())
        // Ordering matters as much as the count: "done" has to be the last thing
        // that happened, not something that raced the body.
        assertEquals("done", services.notifier.titlesAndTexts.last().first)
    }

    @Test
    fun `an empty list runs the body zero times and still completes`() = runBlocking {
        // The interesting half is `completed`. A loop that fell silent on an empty
        // list would be indistinguishable from one wired wrong, and "there was
        // nothing to send" is an outcome rather than a failure.
        run(workflowOver(emptyList()))
        assertTrue(bodyTexts().isEmpty())
        assertEquals(1, completedCount())
    }

    @Test
    fun `a list wired to nothing loops zero times and says so`() = runBlocking {
        val base = workflowOver(listOf("a"))
        val workflow = base.copy(dataConnections = base.dataConnections.filterNot { it.id == "d1" })
        executor.executeFrom(workflow, workflow.node(trigger)!!, TriggerOutput(emptyMap()))
        assertTrue(bodyTexts().isEmpty())
        assertEquals(1, completedCount())
        assertTrue(logs.toString(), logs.any { it.contains("nothing to loop over") })
    }

    @Test
    fun `the index counts from zero, and its conversion is re-pulled each pass`() = runBlocking {
        // Also pins the pull rule across a loop body: the `transform.convert`
        // between the loop and the notify is memoized *per consuming node run*, so
        // it has to produce a different answer on each pass rather than freezing
        // whatever the first iteration saw.
        run(workflowOver(listOf("a", "b", "c"), from = LOOP_INDEX_OUT))
        assertEquals(listOf("0", "1", "2"), bodyTexts())
    }

    @Test
    fun `stopping inside the body stops the loop, not just that pass`() = runBlocking {
        // `action.stop` halts the run. Before `pulse` reported halts upward, the
        // halt unwound one iteration and the loop cheerfully started the next.
        val stop = NodeId("stop")
        val base = workflowOver(listOf("a", "b", "c"))
        val workflow = base.copy(
            nodes = base.nodes + WorkflowNode(stop, NodeTypeId("action.stop"), "Stop", 0f, 250f),
            execConnections = base.execConnections +
                ExecConnection("e4", body, ExecPorts.OUT, stop, ExecPorts.IN),
        )
        run(workflow)
        assertEquals(listOf("a"), bodyTexts())
        assertEquals(0, completedCount())
    }

    @Test
    fun `a loop inside a loop runs the inner one from the start each time`() = runBlocking {
        // Nesting is where a shared cache or a global visited set would show: the
        // two loops key their outputs by their own node ids, and `onPath` adds and
        // removes the inner loop per outer pass rather than remembering it ran.
        val inner = NodeId("inner")
        val innerItems = NodeId("innerItems")
        val base = workflowOver(listOf("a", "b"))
        val workflow = base.copy(
            nodes = base.nodes + listOf(
                WorkflowNode(
                    innerItems, NodeTypeId("transform.split_text"), "Inner items", 0f, 120f,
                    config = mapOf(ConfigKey("text") to "1\n2"),
                ),
                WorkflowNode(inner, NodeTypeId("action.for_each"), "Inner", 0f, 160f),
            ),
            execConnections = listOf(
                ExecConnection("e1", trigger, ExecPorts.OUT, loop, ExecPorts.IN),
                ExecConnection("e2", loop, ExecPorts.BODY, inner, ExecPorts.IN),
                ExecConnection("e3", inner, ExecPorts.BODY, body, ExecPorts.IN),
                ExecConnection("e4", loop, ExecPorts.COMPLETED, after, ExecPorts.IN),
            ),
            dataConnections = listOf(
                DataConnection("d1", source, TRANSFORM_OUT, loop, FOR_EACH_LIST_IN),
                DataConnection("d4", innerItems, TRANSFORM_OUT, inner, FOR_EACH_LIST_IN),
                DataConnection("d2", inner, FOR_EACH_ITEM_OUT, convert, CONVERT_IN),
                DataConnection("d3", convert, TRANSFORM_OUT, body, PortName("text")),
            ),
        )
        run(workflow)
        assertEquals(listOf("1", "2", "1", "2"), bodyTexts())
        assertEquals(1, completedCount())
    }

    /** [workflowOver] with the loop swapped for `action.repeat`, counting [times]. */
    private fun repeatWorkflow(times: String): Workflow {
        val base = workflowOver(emptyList(), from = LOOP_INDEX_OUT)
        return base.copy(
            nodes = base.nodes.map { node ->
                if (node.id != loop) {
                    node
                } else {
                    node.copy(typeId = NodeTypeId("action.repeat"), config = mapOf(ConfigKey("times") to times))
                }
            },
            // Repeat has no list input.
            dataConnections = base.dataConnections.filterNot { it.id == "d1" },
        )
    }

    @Test
    fun `repeat runs its body the configured number of times`() = runBlocking {
        run(repeatWorkflow(times = "3"))
        assertEquals(listOf("0", "1", "2"), bodyTexts())
        assertEquals(1, completedCount())
    }

    @Test
    fun `a runaway repeat is capped and the cap is announced`() = runBlocking {
        run(repeatWorkflow(times = "100000"))
        assertEquals(MAX_ITERATIONS, bodyTexts().size)
        // Silence here would read exactly like a run that covered everything.
        assertTrue(logs.toString(), logs.any { it.contains("capped at $MAX_ITERATIONS") })
    }
}
