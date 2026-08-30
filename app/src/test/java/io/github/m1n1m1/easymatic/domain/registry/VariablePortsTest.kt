package io.github.m1n1m1.easymatic.domain.registry

import io.github.m1n1m1.easymatic.core.model.ConfigKey
import io.github.m1n1m1.easymatic.core.model.NodeId
import io.github.m1n1m1.easymatic.core.model.NodeTypeId
import io.github.m1n1m1.easymatic.core.model.PortName
import io.github.m1n1m1.easymatic.domain.model.DataConnection
import io.github.m1n1m1.easymatic.domain.model.Direction
import io.github.m1n1m1.easymatic.domain.model.PortKind
import io.github.m1n1m1.easymatic.domain.model.VariableDeclaration
import io.github.m1n1m1.easymatic.domain.model.VariableRef
import io.github.m1n1m1.easymatic.domain.model.Workflow
import io.github.m1n1m1.easymatic.domain.model.WorkflowNode
import io.github.m1n1m1.easymatic.domain.model.config.ValueType
import io.github.m1n1m1.easymatic.domain.model.schema.ItemSchema
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a declaration's type does to the two nodes that touch a variable.
 *
 * This is the payoff of declaring a type at all: a counter drops straight into a
 * numeric port, and a number wired at a text variable is a refused drop rather than
 * a silent flattening.
 */
class VariablePortsTest {

    private val counter = VariableDeclaration(id = "c1", name = "counter", type = ValueType.WHOLE_NUMBER)
    private val note = VariableDeclaration(id = "n1", name = "note")
    private val globalCounter = VariableDeclaration(id = "g1", name = "shared", type = ValueType.NUMBER)

    private val reader = NodeId("read")
    private val writer = NodeId("write")

    @After
    fun tearDown() = GlobalVariables.reset()

    private fun workflow(ref: String, declarations: List<VariableDeclaration> = listOf(counter, note)) = Workflow(
        variables = declarations,
        nodes = listOf(
            WorkflowNode(reader, VARIABLE_VALUE_TYPE_ID, "Read", 0f, 0f, config = mapOf(VARIABLE_REF_KEY to ref)),
            WorkflowNode(writer, SET_VARIABLE_TYPE_ID, "Write", 0f, 50f, config = mapOf(VARIABLE_REF_KEY to ref)),
        ),
    )

    private fun readerOutput(workflow: Workflow): ItemSchema? {
        val node = workflow.node(reader)!!
        return effectivePorts(NodeTypeRegistry.byId(node.typeId)!!, workflow, node)
            .single { it.kind == PortKind.DATA && it.direction == Direction.OUT }
            .schema
    }

    private fun writerInput(workflow: Workflow): ItemSchema? {
        val node = workflow.node(writer)!!
        return effectivePorts(NodeTypeRegistry.byId(node.typeId)!!, workflow, node)
            .single { it.kind == PortKind.DATA && it.direction == Direction.IN && it.name == SET_VARIABLE_VALUE_IN }
            .schema
    }

    @Test
    fun `the reader takes its declaration's type`() {
        assertEquals(
            ItemSchema.Primitive(Int::class),
            readerOutput(workflow(VariableRef.localSpec(counter.id))),
        )
    }

    @Test
    fun `the writer's value port takes its declaration's type`() {
        // Which types the *connection*, not the storage: what lands in the store is
        // still the flat text `NodeSchema.decode` produced through `asText()`.
        assertEquals(
            ItemSchema.Primitive(Int::class),
            writerInput(workflow(VariableRef.localSpec(counter.id))),
        )
    }

    @Test
    fun `a global declaration resolves through the published library`() {
        GlobalVariables.hydrate(listOf(globalCounter))
        assertEquals(
            ItemSchema.Primitive(Double::class),
            readerOutput(workflow(VariableRef.globalSpec(globalCounter.id), declarations = emptyList())),
        )
    }

    @Test
    fun `nothing chosen leaves the wildcard alone`() {
        // "Not known yet" is what a wildcard already means everywhere else, and the
        // validator is what says so out loud.
        assertTrue(readerOutput(workflow("")) is ItemSchema.Wildcard)
    }

    @Test
    fun `a reference to a deleted declaration leaves the wildcard alone`() {
        assertTrue(readerOutput(workflow(VariableRef.localSpec("gone"))) is ItemSchema.Wildcard)
    }

    @Test
    fun `a consumer of another family does not retype the port`() {
        // The consumer narrowing exists to pin one primitive out of the *same*
        // family — a Long counter, a Float accuracy. A text port wanting a whole
        // number is a mismatch the user has to convert visibly, not a licence for
        // the variable to change what it says it holds.
        val base = workflow(VariableRef.localSpec(counter.id))
        val notify = NodeId("notify")
        val withConsumer = base.copy(
            nodes = base.nodes + WorkflowNode(
                notify, NodeTypeId("action.notify"), "Notify", 0f, 100f,
                visibleDataInputs = setOf(PortName("text")),
            ),
            dataConnections = listOf(
                DataConnection("d1", reader, PortName("value"), notify, PortName("text")),
            ),
        )
        assertEquals(ItemSchema.Primitive(Int::class), readerOutput(withConsumer))
    }
}
