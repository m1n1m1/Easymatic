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
import io.github.m1n1m1.easymatic.domain.model.VariableDeclaration
import io.github.m1n1m1.easymatic.domain.model.VariableRef
import io.github.m1n1m1.easymatic.domain.model.Workflow
import io.github.m1n1m1.easymatic.domain.model.WorkflowNode
import io.github.m1n1m1.easymatic.domain.registry.FOR_EACH_ITEM_OUT
import io.github.m1n1m1.easymatic.domain.registry.FOR_EACH_LIST_IN
import io.github.m1n1m1.easymatic.domain.registry.JSON_READ_LIST_KEY
import io.github.m1n1m1.easymatic.domain.registry.JSON_READ_TYPE_KEY
import io.github.m1n1m1.easymatic.domain.registry.TRANSFORM_OUT
import io.github.m1n1m1.easymatic.engine.trigger.TriggerOutput
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Collecting a list across a loop, and reading it back.
 *
 * The claim under test is that this needed no change to `VariableStore`: a list
 * variable holds the array's JSON text, which is what [
 * io.github.m1n1m1.easymatic.domain.model.schema.asText] already renders a list as and
 * what `transform.json_read` already parses. If that round trip holds, variables
 * stay flat text and nothing on disk had to learn about lists.
 */
class ListVariablesTest {

    private class FakeStore : Variables {
        val written = LinkedHashMap<String, String>()
        override fun get(ref: String): String? = written[ref]
        override fun set(ref: String, value: String): VariableWrite {
            written[ref] = value
            return VariableWrite.STORED
        }
    }

    private val collected = VariableDeclaration(id = "col", name = "collected")
    private val collectedRef = VariableRef.localSpec(collected.id)

    private val store = FakeStore()
    private val services = RecordingSystemServices()
    private val logs = mutableListOf<String>()
    private val context = DefaultExecutionContext(
        systemServices = services,
        variables = store,
        logger = { logs += it.message },
    )

    /** What the list ends up under, once the run's workflow binding has scoped it. */
    private val collectedKey = VariableRef.storeKey(VariableRef.Local(collected.id), Workflow().id)

    private val trigger = NodeId("trigger")
    private val items = NodeId("items")
    private val clear = NodeId("clear")
    private val loop = NodeId("loop")
    private val add = NodeId("add")

    /**
     * `trigger.manual` -> `action.list_clear` -> `action.for_each` over [values],
     * whose body appends each item to the "collected" variable.
     */
    private fun collectingWorkflow(values: List<String>) = Workflow(
        variables = listOf(collected),
        nodes = listOf(
            WorkflowNode(trigger, NodeTypeId("trigger.manual"), "Manual", 0f, 0f),
            WorkflowNode(
                items, NodeTypeId("transform.split_text"), "Items", 0f, 50f,
                config = mapOf(ConfigKey("text") to values.joinToString("\n")),
            ),
            WorkflowNode(
                clear, NodeTypeId("action.list_clear"), "Empty", 0f, 100f,
                config = mapOf(ConfigKey("name") to collectedRef),
            ),
            WorkflowNode(loop, NodeTypeId("action.for_each"), "For each", 0f, 150f),
            WorkflowNode(
                add, NodeTypeId("action.list_add"), "Add", 0f, 200f,
                config = mapOf(ConfigKey("name") to collectedRef),
            ),
        ),
        execConnections = listOf(
            ExecConnection("e1", trigger, ExecPorts.OUT, clear, ExecPorts.IN),
            ExecConnection("e2", clear, ExecPorts.OUT, loop, ExecPorts.IN),
            ExecConnection("e3", loop, ExecPorts.BODY, add, ExecPorts.IN),
        ),
        dataConnections = listOf(
            DataConnection("d1", items, TRANSFORM_OUT, loop, FOR_EACH_LIST_IN),
            DataConnection("d2", loop, FOR_EACH_ITEM_OUT, add, PortName("value")),
        ),
    )

    /**
     * Runs [workflow] through a context bound to it, which is what `WorkflowRunner`
     * does once per arm — and what turns the nodes' refs into scoped store keys.
     */
    private suspend fun run(workflow: Workflow) {
        WorkflowExecutor(context.boundTo(workflow))
            .executeFrom(workflow, workflow.node(trigger)!!, TriggerOutput(emptyMap()))
        assertTrue(logs.toString(), logs.none { it.contains("problem") })
    }

    @Test
    fun `a loop collects into a list variable`() = runBlocking {
        run(collectingWorkflow(listOf("a", "b", "c")))
        assertEquals("""["a","b","c"]""", store.written[collectedKey])
    }

    @Test
    fun `emptying first is what stops one run appending to the last`() = runBlocking {
        val workflow = collectingWorkflow(listOf("a"))
        run(workflow)
        run(workflow)
        assertEquals("""["a"]""", store.written[collectedKey])
    }

    @Test
    fun `without emptying first, a second run appends`() = runBlocking {
        // The reason `action.list_clear` exists as its own node rather than as a
        // hidden reset inside the loop.
        val base = collectingWorkflow(listOf("a"))
        val workflow = base.copy(
            execConnections = listOf(
                ExecConnection("e1", trigger, ExecPorts.OUT, loop, ExecPorts.IN),
                ExecConnection("e3", loop, ExecPorts.BODY, add, ExecPorts.IN),
            ),
        )
        run(workflow)
        run(workflow)
        assertEquals("""["a","a"]""", store.written[collectedKey])
    }

    @Test
    fun `the collected list reads back as a real list`() = runBlocking {
        // value.variable hands over the JSON text; json_read in list mode turns it
        // back into a list. This is the whole round trip that lets the store stay
        // flat text.
        run(collectingWorkflow(listOf("x", "y")))
        val read = readBack(store.written[collectedKey]!!)
        assertEquals(listOf("x", "y"), read)
    }

    @Test
    fun `an item keeps its type through the collection`() = runBlocking {
        // Appending goes through the raw Item rather than through asText(), so a
        // number is stored as a JSON number and reads back as one.
        val base = collectingWorkflow(listOf("1", "2"))
        val convert = NodeId("convert")
        val workflow = base.copy(
            nodes = base.nodes + WorkflowNode(
                convert, NodeTypeId("transform.convert"), "Convert", 0f, 175f,
                config = mapOf(ConfigKey("to") to "WHOLE_NUMBER"),
            ),
            dataConnections = listOf(
                base.dataConnections.first(),
                DataConnection("d2", loop, FOR_EACH_ITEM_OUT, convert, PortName("in")),
                DataConnection("d3", convert, TRANSFORM_OUT, add, PortName("value")),
            ),
        )
        run(workflow)
        assertEquals("[1,2]", store.written[collectedKey])
    }

    @Test
    fun `adding with nothing wired leaves the list alone and says why`() = runBlocking {
        val base = collectingWorkflow(listOf("a"))
        val workflow = base.copy(dataConnections = base.dataConnections.filterNot { it.id == "d2" })
        run(workflow)
        assertEquals("[]", store.written[collectedKey])
        assertTrue(logs.toString(), logs.any { it.contains("nothing wired in") })
    }

    @Test
    fun `both list actions refuse a constant, and adding does not even read it`() = runBlocking {
        val fixed = VariableDeclaration(id = "col", name = "collected", initialValue = "[\"kept\"]", constant = true)
        val workflow = collectingWorkflow(listOf("a")).copy(variables = listOf(fixed))
        run(workflow)
        // Nothing was written by either node — and `action.list_add` refused before
        // its read-modify-write, so the constant's own value is untouched.
        assertTrue(store.written.toString(), store.written.isEmpty())
        assertEquals(2, logs.count { it.contains("'collected' is a constant") })
    }

    /** Reads [json] back through `transform.json_read` in list mode. */
    private suspend fun readBack(json: String): Any? {
        val node = WorkflowNode(
            NodeId("read"), NodeTypeId("transform.json_read"), "Read", 0f, 0f,
            config = mapOf(
                ConfigKey("json") to json,
                JSON_READ_TYPE_KEY to "TEXT",
                JSON_READ_LIST_KEY to "true",
            ),
        )
        val transform = io.github.m1n1m1.easymatic.domain.registry.TransformRegistry
            .byId(NodeTypeId("transform.json_read"))!!
        return transform.transformRaw(node, emptyMap(), context)?.value
    }
}

