package com.example.ottomatic.engine

import com.example.ottomatic.core.model.ConfigKey
import com.example.ottomatic.core.model.NodeId
import com.example.ottomatic.core.model.NodeTypeId
import com.example.ottomatic.core.model.PortName
import com.example.ottomatic.core.service.Variables
import com.example.ottomatic.domain.model.DataConnection
import com.example.ottomatic.domain.model.ExecConnection
import com.example.ottomatic.domain.model.ExecPorts
import com.example.ottomatic.domain.model.Workflow
import com.example.ottomatic.domain.model.WorkflowNode
import com.example.ottomatic.domain.registry.FOR_EACH_ITEM_OUT
import com.example.ottomatic.domain.registry.FOR_EACH_LIST_IN
import com.example.ottomatic.domain.registry.JSON_READ_LIST_KEY
import com.example.ottomatic.domain.registry.JSON_READ_TYPE_KEY
import com.example.ottomatic.domain.registry.TRANSFORM_OUT
import com.example.ottomatic.engine.trigger.TriggerOutput
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Collecting a list across a loop, and reading it back.
 *
 * The claim under test is that this needed no change to `VariableStore`: a list
 * variable holds the array's JSON text, which is what [
 * com.example.ottomatic.domain.model.schema.asText] already renders a list as and
 * what `transform.json_read` already parses. If that round trip holds, variables
 * stay flat text and nothing on disk had to learn about lists.
 */
class ListVariablesTest {

    private class FakeVariables : Variables {
        val written = LinkedHashMap<String, String>()
        override fun get(name: String): String? = written[name]
        override fun set(name: String, value: String) {
            written[name] = value
        }
    }

    private val variables = FakeVariables()
    private val services = RecordingSystemServices()
    private val logs = mutableListOf<String>()
    private val context = DefaultExecutionContext(
        systemServices = services,
        variables = variables,
        logger = { logs += it.message },
    )
    private val executor = WorkflowExecutor(context)

    private val trigger = NodeId("trigger")
    private val items = NodeId("items")
    private val clear = NodeId("clear")
    private val loop = NodeId("loop")
    private val add = NodeId("add")

    /**
     * `trigger.manual` → `action.list_clear` → `action.for_each` over [values],
     * whose body appends each item to the "collected" variable.
     */
    private fun collectingWorkflow(values: List<String>) = Workflow(
        nodes = listOf(
            WorkflowNode(trigger, NodeTypeId("trigger.manual"), "Manual", 0f, 0f),
            WorkflowNode(
                items, NodeTypeId("transform.split_text"), "Items", 0f, 50f,
                config = mapOf(ConfigKey("text") to values.joinToString("\n")),
            ),
            WorkflowNode(
                clear, NodeTypeId("action.list_clear"), "Empty", 0f, 100f,
                config = mapOf(ConfigKey("name") to "collected"),
            ),
            WorkflowNode(loop, NodeTypeId("action.for_each"), "For each", 0f, 150f),
            WorkflowNode(
                add, NodeTypeId("action.list_add"), "Add", 0f, 200f,
                config = mapOf(ConfigKey("name") to "collected"),
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

    private suspend fun run(workflow: Workflow) {
        executor.executeFrom(workflow, workflow.node(trigger)!!, TriggerOutput(emptyMap()))
        assertTrue(logs.toString(), logs.none { it.contains("problem") })
    }

    @Test
    fun `a loop collects into a list variable`() = runBlocking {
        run(collectingWorkflow(listOf("a", "b", "c")))
        assertEquals("""["a","b","c"]""", variables.written["collected"])
    }

    @Test
    fun `emptying first is what stops one run appending to the last`() = runBlocking {
        val workflow = collectingWorkflow(listOf("a"))
        run(workflow)
        run(workflow)
        assertEquals("""["a"]""", variables.written["collected"])
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
        assertEquals("""["a","a"]""", variables.written["collected"])
    }

    @Test
    fun `the collected list reads back as a real list`() = runBlocking {
        // value.variable hands over the JSON text; json_read in list mode turns it
        // back into a list. This is the whole round trip that lets the store stay
        // flat text.
        run(collectingWorkflow(listOf("x", "y")))
        val read = readBack(variables.written["collected"]!!)
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
        assertEquals("[1,2]", variables.written["collected"])
    }

    @Test
    fun `adding with nothing wired leaves the list alone and says why`() = runBlocking {
        val base = collectingWorkflow(listOf("a"))
        val workflow = base.copy(dataConnections = base.dataConnections.filterNot { it.id == "d2" })
        run(workflow)
        assertEquals("[]", variables.written["collected"])
        assertTrue(logs.toString(), logs.any { it.contains("nothing wired in") })
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
        val transform = com.example.ottomatic.domain.registry.TransformRegistry
            .byId(NodeTypeId("transform.json_read"))!!
        return transform.transformRaw(node, emptyMap(), context)?.value
    }
}
