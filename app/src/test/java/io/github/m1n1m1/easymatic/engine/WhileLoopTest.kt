package io.github.m1n1m1.easymatic.engine

import io.github.m1n1m1.easymatic.core.model.ConfigKey
import io.github.m1n1m1.easymatic.core.model.NodeId
import io.github.m1n1m1.easymatic.core.model.NodeTypeId
import io.github.m1n1m1.easymatic.core.model.PortName
import io.github.m1n1m1.easymatic.core.service.VariableWrite
import io.github.m1n1m1.easymatic.core.service.Variables
import io.github.m1n1m1.easymatic.domain.model.DataConnection
import io.github.m1n1m1.easymatic.domain.model.ExecConnection
import io.github.m1n1m1.easymatic.domain.model.ExecPorts
import io.github.m1n1m1.easymatic.domain.model.ValueSource
import io.github.m1n1m1.easymatic.domain.model.VariableDeclaration
import io.github.m1n1m1.easymatic.domain.model.VariableRef
import io.github.m1n1m1.easymatic.domain.model.Workflow
import io.github.m1n1m1.easymatic.domain.model.WorkflowNode
import io.github.m1n1m1.easymatic.domain.model.config.ComparisonOperator
import io.github.m1n1m1.easymatic.domain.model.config.ComparisonType
import io.github.m1n1m1.easymatic.domain.registry.CONVERT_IN
import io.github.m1n1m1.easymatic.domain.registry.CONVERT_TO_KEY
import io.github.m1n1m1.easymatic.domain.registry.IF_OPERATOR_KEY
import io.github.m1n1m1.easymatic.domain.registry.IF_SOURCE_KEY
import io.github.m1n1m1.easymatic.domain.registry.IF_TYPE_CONFIG_KEY
import io.github.m1n1m1.easymatic.domain.registry.IF_TYPE_ID
import io.github.m1n1m1.easymatic.domain.registry.LOOP_INDEX_OUT
import io.github.m1n1m1.easymatic.domain.registry.TRANSFORM_OUT
import io.github.m1n1m1.easymatic.domain.registry.WHILE_TYPE_ID
import io.github.m1n1m1.easymatic.engine.trigger.TriggerOutput
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `action.while` — the loop whose length nobody states.
 *
 * Two things carry it, and both are pinned here: the condition is re-read every pass
 * (or the body could never end it), and a condition that never goes false is stopped
 * at [MAX_ITERATIONS] rather than spinning for as long as the process lives.
 *
 * Every graph below is one the editor would accept. That matters more than usual for
 * this node: wiring a loop's own output back into its own condition looks like the
 * obvious way to write a counter, but it is a *data* cycle, which `GraphValidator`
 * blocks — so the honest shape reads the condition from a variable the body writes,
 * which is also what a user would build.
 */
class WhileLoopTest {

    private class FakeStore : Variables {
        val written = LinkedHashMap<String, String>()
        override fun get(ref: String): String? = written[ref]
        override fun set(ref: String, value: String): VariableWrite {
            written[ref] = value
            return VariableWrite.STORED
        }
    }

    /** The flag the body writes and the condition reads. */
    private val flag = VariableDeclaration(id = "flag", name = "flag")
    private val flagRef = VariableRef.localSpec(flag.id)

    private val variables = FakeStore()
    private val services = RecordingSystemServices()
    private val logs = mutableListOf<String>()
    private val context = DefaultExecutionContext(
        systemServices = services,
        variables = variables,
        notifications = services.notifier,
        logger = { logs += it.message },
    )

    private val trigger = NodeId("trigger")
    private val init = NodeId("init")
    private val counter = NodeId("counter")
    private val loop = NodeId("loop")
    private val convert = NodeId("convert")
    private val body = NodeId("body")
    private val guard = NodeId("guard")
    private val finish = NodeId("finish")
    private val after = NodeId("after")

    /**
     * The realistic counter loop, built only from nodes that already exist:
     *
     * ```
     * manual → set "flag" = go → while (flag ≠ stop)
     *                              body → notify(index) → if (index ≥ limit) → set "flag" = stop
     *                              completed → notify "done"
     * ```
     *
     * The condition reads `value.variable` over a **data edge from a value node** —
     * pull-side, so it is exempt from the exec-upstream rule and forms no cycle — and
     * the body writes that same variable. If the condition were evaluated once, this
     * would run to the cap; that it stops at [limit] is the re-read.
     *
     * [initialise] off leaves the variable unset, which is how the condition reads
     * false from the very first check.
     */
    private fun counterWorkflow(limit: String, initialise: Boolean = true): Workflow {
        val entry = if (initialise) init else loop
        return Workflow(
            variables = listOf(flag),
            nodes = counterNodes(limit, initialise),
            execConnections = listOfNotNull(
                ExecConnection("e0", trigger, ExecPorts.OUT, entry, ExecPorts.IN),
                ExecConnection("e1", init, ExecPorts.OUT, loop, ExecPorts.IN).takeIf { initialise },
                ExecConnection("e2", loop, ExecPorts.BODY, body, ExecPorts.IN),
                ExecConnection("e3", body, ExecPorts.OUT, guard, ExecPorts.IN),
                ExecConnection("e4", guard, ExecPorts.TRUE, finish, ExecPorts.IN),
                ExecConnection("e5", loop, ExecPorts.COMPLETED, after, ExecPorts.IN),
            ),
            dataConnections = listOf(
                DataConnection("d1", counter, PortName("value"), loop, PortName("source")),
                DataConnection("d2", loop, LOOP_INDEX_OUT, convert, CONVERT_IN),
                DataConnection("d3", convert, TRANSFORM_OUT, body, PortName("text")),
                DataConnection("d4", loop, LOOP_INDEX_OUT, guard, PortName("source")),
            ),
        )
    }

    /** The nodes of [counterWorkflow]; split out only to keep the builder readable. */
    private fun counterNodes(limit: String, initialise: Boolean): List<WorkflowNode> = listOfNotNull(
        WorkflowNode(trigger, NodeTypeId("trigger.manual"), "Manual", 0f, 0f),
        WorkflowNode(
            init, NodeTypeId("action.set_variable"), "Start", 0f, 50f,
            config = mapOf(ConfigKey("name") to flagRef, ConfigKey("value") to "go"),
        ).takeIf { initialise },
        WorkflowNode(
            counter, NodeTypeId("value.variable"), "Flag", 200f, 80f,
            config = mapOf(ConfigKey("name") to flagRef),
        ),
        WorkflowNode(
            loop, WHILE_TYPE_ID, "Repeat while", 0f, 100f,
            config = mapOf(
                IF_SOURCE_KEY to ValueSource.WIRED_SPEC,
                IF_OPERATOR_KEY to ComparisonOperator.NOT_EQUALS.name,
                ConfigKey("value") to "stop",
            ),
        ),
        WorkflowNode(
            convert, NodeTypeId("transform.convert"), "Convert", 0f, 150f,
            config = mapOf(CONVERT_TO_KEY to "TEXT"),
        ),
        WorkflowNode(
            body, NodeTypeId("action.notify"), "Body", 0f, 200f,
            config = mapOf(ConfigKey("title") to "body"),
        ),
        WorkflowNode(
            guard, IF_TYPE_ID, "Enough?", 0f, 250f,
            config = mapOf(
                IF_SOURCE_KEY to ValueSource.WIRED_SPEC,
                IF_TYPE_CONFIG_KEY to ComparisonType.INT.name,
                IF_OPERATOR_KEY to ComparisonOperator.GREATER_THAN_OR_EQUAL.name,
                ConfigKey("value") to limit,
            ),
        ),
        WorkflowNode(
            finish, NodeTypeId("action.set_variable"), "Stop", 0f, 300f,
            config = mapOf(ConfigKey("name") to flagRef, ConfigKey("value") to "stop"),
        ),
        WorkflowNode(
            after, NodeTypeId("action.notify"), "After", 0f, 350f,
            config = mapOf(ConfigKey("title") to "done", ConfigKey("text") to "finished"),
        ),
    )

    /** Bound to the workflow, as `WorkflowRunner` binds it once per arm. */
    private suspend fun run(workflow: Workflow) {
        WorkflowExecutor(context.boundTo(workflow))
            .executeFrom(workflow, workflow.node(trigger)!!, TriggerOutput(emptyMap()))
        // A blocked node is skipped in near-silence, so a graph that failed validation
        // would otherwise present as "the loop ran zero times".
        assertTrue(logs.toString(), logs.none { it.contains("problem") })
    }

    private fun bodyTexts() = services.notifier.titlesAndTexts.filter { it.first == "body" }.map { it.second }

    private fun completedCount() = services.notifier.titlesAndTexts.count { it.first == "done" }

    @Test
    fun `it repeats while the condition holds and stops once the body changes it`() = runBlocking {
        run(counterWorkflow(limit = "2"))
        // Passes 0, 1 and 2; on the third the guard fires and sets the flag, so the
        // fourth check is false. A condition evaluated once would have hit the cap.
        assertEquals(listOf("0", "1", "2"), bodyTexts())
        assertEquals(1, completedCount())
    }

    @Test
    fun `the index advances across passes`() = runBlocking {
        run(counterWorkflow(limit = "4"))
        assertEquals(listOf("0", "1", "2", "3", "4"), bodyTexts())
    }

    @Test
    fun `a condition that is false to begin with runs the body zero times`() = runBlocking {
        // The flag is never set, so the very first read is unavailable and the
        // comparison fails closed. Matches an empty list in `action.for_each`:
        // nothing to do is an outcome, and `completed` still fires.
        run(counterWorkflow(limit = "2", initialise = false))
        assertTrue(bodyTexts().isEmpty())
        assertEquals(1, completedCount())
    }

    @Test
    fun `a condition that never goes false is stopped and says so`() = runBlocking {
        // The guard can never fire, so nothing ever writes the flag. This is the
        // safeguard that matters most for this node, because nobody stated a length.
        run(counterWorkflow(limit = "999999"))
        assertEquals(MAX_ITERATIONS, bodyTexts().size)
        assertTrue(
            logs.toString(),
            logs.any { it.contains("stopped after $MAX_ITERATIONS") && it.contains("never went false") },
        )
        // It still finishes rather than abandoning the branch in silence.
        assertEquals(1, completedCount())
    }

    @Test
    fun `stopping inside the body stops the loop`() = runBlocking {
        val stop = NodeId("stop")
        val base = counterWorkflow(limit = "999999")
        val workflow = base.copy(
            nodes = base.nodes + WorkflowNode(stop, NodeTypeId("action.stop"), "Stop", 0f, 400f),
            execConnections = base.execConnections +
                ExecConnection("e6", guard, ExecPorts.FALSE, stop, ExecPorts.IN),
        )
        run(workflow)
        assertEquals(listOf("0"), bodyTexts())
        assertEquals(0, completedCount())
    }

    @Test
    fun `its card carries the loop exec ports, the comparison inputs and an index`() {
        // `comparisonEffectivePorts` replaces the declared port set wholesale, so a
        // port left out of it disappears from the card — this is the check that the
        // node is wired up as a loop rather than silently rendering as a branch.
        val node = WorkflowNode(loop, WHILE_TYPE_ID, "Repeat while", 0f, 0f)
        val workflow = Workflow(nodes = listOf(node))
        val definition = io.github.m1n1m1.easymatic.domain.registry.NodeTypeRegistry.byId(WHILE_TYPE_ID)!!
        val ports = io.github.m1n1m1.easymatic.domain.registry.effectivePorts(definition, workflow, node)

        assertEquals(
            listOf(ExecPorts.IN, ExecPorts.BODY, ExecPorts.COMPLETED),
            ports.filter { it.kind == io.github.m1n1m1.easymatic.domain.model.PortKind.EXECUTION }.map { it.name },
        )
        assertEquals(
            listOf(PortName("source"), PortName("value")),
            ports.filter {
                it.kind == io.github.m1n1m1.easymatic.domain.model.PortKind.DATA &&
                    it.direction == io.github.m1n1m1.easymatic.domain.model.Direction.IN
            }.map { it.name },
        )
        assertEquals(
            listOf(LOOP_INDEX_OUT),
            ports.filter {
                it.kind == io.github.m1n1m1.easymatic.domain.model.PortKind.DATA &&
                    it.direction == io.github.m1n1m1.easymatic.domain.model.Direction.OUT
            }.map { it.name },
        )
    }

    @Test
    fun `it shares one comparison with action if rather than growing a second`() {
        // Both read their answer from the same `evaluateCompare`, so the two can
        // never disagree about what "greater than" means. The observable half of
        // that is the config form: identical fields on both nodes.
        val ifFields = io.github.m1n1m1.easymatic.domain.registry.ConfigSchemaRegistry
            .byId(IF_TYPE_ID)!!.fields.map { it.key }
        val whileFields = io.github.m1n1m1.easymatic.domain.registry.ConfigSchemaRegistry
            .byId(WHILE_TYPE_ID)!!.fields.map { it.key }
        assertEquals(ifFields, whileFields)
    }
}
