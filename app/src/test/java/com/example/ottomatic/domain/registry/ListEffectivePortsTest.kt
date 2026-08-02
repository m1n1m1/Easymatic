package com.example.ottomatic.domain.registry

import com.example.ottomatic.core.model.ConfigKey
import com.example.ottomatic.core.model.NodeId
import com.example.ottomatic.core.model.NodeTypeId
import com.example.ottomatic.core.model.PortName
import com.example.ottomatic.domain.model.ANY_LIST
import com.example.ottomatic.domain.model.DataConnection
import com.example.ottomatic.domain.model.Direction
import com.example.ottomatic.domain.model.ExecPorts
import com.example.ottomatic.domain.model.PortKind
import com.example.ottomatic.domain.model.Workflow
import com.example.ottomatic.domain.model.WorkflowNode
import com.example.ottomatic.domain.model.schema.ItemSchema
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The design-time half of lists: what a placed node's ports actually are once the
 * graph is taken into account.
 *
 * This is what makes a loop over a list of numbers give a *number* port, so that
 * wiring it into a text field is a refused drop at the moment it is drawn rather
 * than a surprise at run time.
 */
class ListEffectivePortsTest {

    private val source = NodeId("source")
    private val target = NodeId("target")

    /**
     * A graph where [typeId] takes its list from a `transform.split_text` node —
     * the shortest way to name a list of text — on the port named [into].
     */
    private fun graph(typeId: NodeTypeId, into: PortName, config: Map<ConfigKey, String> = emptyMap()): Workflow =
        Workflow(
            id = "w",
            name = "w",
            nodes = listOf(
                WorkflowNode(source, NodeTypeId("transform.split_text"), "Items", 0f, 0f),
                WorkflowNode(target, typeId, "Target", 0f, 100f, config = config),
            ),
            dataConnections = listOf(
                DataConnection("d1", source, TRANSFORM_OUT, target, into),
            ),
        )

    private fun portsOf(workflow: Workflow): List<com.example.ottomatic.domain.model.Port> {
        val node = workflow.node(target)!!
        return effectivePorts(NodeTypeRegistry.byId(node.typeId)!!, workflow, node)
    }

    private fun outputSchema(workflow: Workflow, port: PortName): ItemSchema? =
        portsOf(workflow).firstOrNull { it.name == port && it.direction == Direction.OUT }?.schema

    private val text = ItemSchema.Primitive(String::class)

    @Test
    fun `for each types its item port from the list wired in`() {
        val schema = outputSchema(graph(FOR_EACH_TYPE_ID, FOR_EACH_LIST_IN), FOR_EACH_ITEM_OUT)
        assertEquals(text, schema)
    }

    @Test
    fun `for each with nothing wired offers a wildcard item`() {
        // So the node can be placed before the thing that feeds it.
        val workflow = Workflow(
            id = "w",
            name = "w",
            nodes = listOf(WorkflowNode(target, FOR_EACH_TYPE_ID, "Target", 0f, 0f)),
        )
        assertEquals(ItemSchema.Wildcard, outputSchema(workflow, FOR_EACH_ITEM_OUT))
    }

    @Test
    fun `for each keeps its exec ports when its data ports are resolved`() {
        // The resolution replaces the declared port list wholesale, so anything
        // left out of it simply vanishes from the card.
        val ports = portsOf(graph(FOR_EACH_TYPE_ID, FOR_EACH_LIST_IN))
        val exec = ports.filter { it.kind == PortKind.EXECUTION }.map { it.name }
        assertEquals(listOf(ExecPorts.IN, ExecPorts.BODY, ExecPorts.COMPLETED), exec)
    }

    @Test
    fun `for each always offers an index, whatever it is looping over`() {
        val ports = portsOf(graph(FOR_EACH_TYPE_ID, FOR_EACH_LIST_IN))
        val index = ports.firstOrNull { it.name == LOOP_INDEX_OUT && it.direction == Direction.OUT }
        assertNotNull(index)
        assertEquals(ItemSchema.Primitive(Int::class), index!!.schema)
    }

    @Test
    fun `for each accepts any list and nothing else`() {
        val list = portsOf(graph(FOR_EACH_TYPE_ID, FOR_EACH_LIST_IN))
            .first { it.name == FOR_EACH_LIST_IN && it.direction == Direction.IN }
        assertEquals(ANY_LIST, list.schema)
    }

    @Test
    fun `item at answers with the element type`() {
        assertEquals(text, outputSchema(graph(LIST_ITEM_TYPE_ID, LIST_IN), TRANSFORM_OUT))
    }

    @Test
    fun `sort and slice answer with the same list they were given`() {
        val listOfText = ItemSchema.ListSchema(text)
        assertEquals(listOfText, outputSchema(graph(LIST_SORT_TYPE_ID, LIST_IN), TRANSFORM_OUT))
        assertEquals(listOfText, outputSchema(graph(LIST_SLICE_TYPE_ID, LIST_IN), TRANSFORM_OUT))
    }

    @Test
    fun `a JSON read in list mode produces a list of the chosen type`() {
        val workflow = Workflow(
            id = "w",
            name = "w",
            nodes = listOf(
                WorkflowNode(
                    target, JSON_READ_TYPE_ID, "Read", 0f, 0f,
                    config = mapOf(JSON_READ_TYPE_KEY to "WHOLE_NUMBER", JSON_READ_LIST_KEY to "true"),
                ),
            ),
        )
        assertEquals(ItemSchema.ListSchema(ItemSchema.Primitive(Int::class)), outputSchema(workflow, TRANSFORM_OUT))
    }

    @Test
    fun `a JSON read without list mode is unchanged`() {
        val workflow = Workflow(
            id = "w",
            name = "w",
            nodes = listOf(
                WorkflowNode(target, JSON_READ_TYPE_ID, "Read", 0f, 0f, config = mapOf(JSON_READ_TYPE_KEY to "TEXT")),
            ),
        )
        assertEquals(text, outputSchema(workflow, TRANSFORM_OUT))
    }

    @Test
    fun `a script declaring a list port gets a list port`() {
        val workflow = Workflow(
            id = "w",
            name = "w",
            nodes = listOf(
                WorkflowNode(
                    target, SCRIPT_TYPE_ID, "Script", 0f, 0f,
                    config = mapOf(SCRIPT_OUTPUTS_KEY to "values:WHOLE_NUMBER[]"),
                ),
            ),
        )
        val port = outputSchema(workflow, PortName("values"))
        assertEquals(ItemSchema.ListSchema(ItemSchema.Primitive(Int::class)), port)
    }

    @Test
    fun `a list of text from a script drops straight into a loop`() {
        // The end-to-end claim: a script is the general list producer, and its
        // output needs no conversion node to be iterated.
        val script = WorkflowNode(
            source, SCRIPT_TYPE_ID, "Script", 0f, 0f,
            config = mapOf(SCRIPT_OUTPUTS_KEY to "names:TEXT[]"),
        )
        val loop = WorkflowNode(target, FOR_EACH_TYPE_ID, "For each", 0f, 100f)
        val workflow = Workflow(id = "w", name = "w", nodes = listOf(script, loop))
        val from = effectivePorts(NodeTypeRegistry.byId(SCRIPT_TYPE_ID)!!, workflow, script)
            .first { it.name == PortName("names") && it.direction == Direction.OUT }
        val into = effectivePorts(NodeTypeRegistry.byId(FOR_EACH_TYPE_ID)!!, workflow, loop)
            .first { it.name == FOR_EACH_LIST_IN && it.direction == Direction.IN }
        assertTrue(isDataAssignable(from, into))
    }
}
