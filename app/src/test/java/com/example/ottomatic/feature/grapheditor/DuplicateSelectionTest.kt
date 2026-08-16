package com.example.ottomatic.feature.grapheditor

import com.example.ottomatic.core.model.ConfigKey
import com.example.ottomatic.core.model.NodeId
import com.example.ottomatic.core.model.NodeTypeId
import com.example.ottomatic.core.model.PortName
import com.example.ottomatic.domain.model.DataConnection
import com.example.ottomatic.domain.model.ExecConnection
import com.example.ottomatic.domain.model.Workflow
import com.example.ottomatic.domain.model.WorkflowNode
import com.example.ottomatic.domain.registry.API_TOKEN_KEY
import com.example.ottomatic.domain.registry.API_TRIGGER_TYPE_ID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Duplication is the pull-side of [SelectionOpsTest]'s subject: the same selection,
 * copied rather than removed. The interesting cases are all about *edges* — which
 * ones come along, which are left behind, and whether the originals survive the
 * remapping untouched.
 *
 * Ids are injected rather than generated so the assertions can name them; the real
 * caller passes `UUID.randomUUID()`, which would leave nothing to assert against.
 */
class DuplicateSelectionTest {

    @Test
    fun `a copied node keeps everything but its id and position`() {
        val result = workflow().withDuplicated(Selection.ofNode(B), ids())

        val copy = result.workflow.nodes.single { it.id == NodeId("n1") }
        val original = workflow().nodes.single { it.id == B }
        assertEquals(original.typeId, copy.typeId)
        assertEquals(original.name, copy.name)
        assertEquals(original.config, copy.config)
        assertEquals(original.visibleDataInputs, copy.visibleDataInputs)
        assertEquals(original.x + DUPLICATE_OFFSET, copy.x, 0.001f)
        assertEquals(original.y + DUPLICATE_OFFSET, copy.y, 0.001f)
    }

    @Test
    fun `the copies become the selection`() {
        val result = workflow().withDuplicated(Selection(nodeIds = setOf(A, B)), ids())

        assertEquals(setOf(NodeId("n1"), NodeId("n2")), result.selection.nodeIds)
        assertTrue("edges are never selected by a duplicate", result.selection.connectionIds.isEmpty())
    }

    @Test
    fun `an edge inside the selection is copied and repointed at the copies`() {
        // A -> B is wholly inside the selection, so the pair duplicates as a pair.
        val result = workflow().withDuplicated(Selection(nodeIds = setOf(A, B)), ids())

        val copied = result.workflow.execConnections.single { it.id !in setOf(EXEC_EDGE) }
        assertEquals(NodeId("n1"), copied.fromNodeId)
        assertEquals(NodeId("n2"), copied.toNodeId)
        // Ports are structural, not identity — they must survive verbatim.
        assertEquals(PortName("out"), copied.fromPort)
        assertEquals(PortName("in"), copied.toPort)
    }

    @Test
    fun `a data edge inside the selection is copied too`() {
        val result = workflow().withDuplicated(Selection(nodeIds = setOf(B, C)), ids())

        assertEquals(2, result.workflow.dataConnections.size)
        val copied = result.workflow.dataConnections.single { it.id != DATA_EDGE }
        assertEquals(NodeId("n1"), copied.fromNodeId)
        assertEquals(NodeId("n2"), copied.toNodeId)
    }

    @Test
    fun `an edge crossing the selection boundary is dropped`() {
        // Only B is taken, so A->B and B->C each have one end with nowhere to land.
        // Copying either would either dangle or silently fan A into a second consumer.
        val result = workflow().withDuplicated(Selection.ofNode(B), ids())

        assertEquals(listOf(EXEC_EDGE), result.workflow.execConnections.map { it.id })
        assertEquals(listOf(DATA_EDGE), result.workflow.dataConnections.map { it.id })
    }

    @Test
    fun `the originals are left exactly as they were`() {
        val before = workflow()

        val result = before.withDuplicated(Selection(nodeIds = setOf(A, B)), ids())

        assertEquals(before.nodes, result.workflow.nodes.take(before.nodes.size))
        assertTrue(result.workflow.execConnections.containsAll(before.execConnections))
        assertTrue(result.workflow.dataConnections.containsAll(before.dataConnections))
    }

    @Test
    fun `every copied edge gets a fresh id`() {
        val result = workflow().withDuplicated(Selection(nodeIds = setOf(A, B)), ids())

        val ids = result.workflow.execConnections.map { it.id }
        assertEquals("duplicate edge ids would collide on delete", ids.size, ids.toSet().size)
    }

    @Test
    fun `a selection of only edges duplicates nothing`() {
        // An edge has no existence apart from the two nodes it joins.
        val before = workflow()

        val result = before.withDuplicated(Selection.ofConnection(EXEC_EDGE), ids())

        assertEquals(before, result.workflow)
    }

    @Test
    fun `an empty selection duplicates nothing`() {
        val before = workflow()

        assertEquals(before, before.withDuplicated(Selection.EMPTY, ids()).workflow)
    }

    @Test
    fun `a duplicated api trigger gets a fresh token`() {
        // The token is a bearer credential, and the only one the broadcast door has.
        // Two triggers behind one secret would make rotating either of them a lie.
        val original = WorkflowNode(
            A,
            API_TRIGGER_TYPE_ID,
            "Called by Another App",
            0f,
            0f,
            config = mapOf(API_TOKEN_KEY to "original-token", ConfigKey("label") to "Kitchen"),
        )
        val workflow = Workflow(nodes = listOf(original))

        val copy = workflow.withDuplicated(Selection.ofNode(A), ids()).workflow.nodes.last()

        val token = copy.config[API_TOKEN_KEY]
        assertNotEquals("original-token", token)
        assertTrue("a generated token is always callable", !token.isNullOrBlank())
        // Everything that is not the credential still comes across.
        assertEquals("Kitchen", copy.config[ConfigKey("label")])
    }

    @Test
    fun `an ordinary node's config is copied byte for byte`() {
        val result = workflow().withDuplicated(Selection.ofNode(B), ids())

        assertEquals(mapOf(ConfigKey("url") to "https://example.com"), result.workflow.nodes.last().config)
    }

    /**
     * Node ids handed out in order, so a copy can be named in an assertion.
     *
     * Nodes are visited in the order they appear in [Workflow.nodes], which is what
     * lets `n1`/`n2` above mean "the copy of A" and "the copy of B". Edge ids are
     * left to the default UUID — nothing here asserts on their value, only that they
     * are fresh and distinct.
     */
    private fun ids(): () -> NodeId {
        var next = 0
        return { NodeId("n${++next}") }
    }

    /** A -(exec)-> B -(data)-> C, so any single-node selection has a boundary edge. */
    private fun workflow() = Workflow(
        nodes = listOf(
            WorkflowNode(A, NodeTypeId("trigger.manual"), "Manual", 0f, 0f),
            WorkflowNode(
                B,
                NodeTypeId("action.http"),
                "HTTP",
                10f,
                100f,
                config = mapOf(ConfigKey("url") to "https://example.com"),
                visibleDataInputs = setOf(PortName("url")),
            ),
            WorkflowNode(C, NodeTypeId("action.notify"), "Notify", 0f, 200f),
        ),
        execConnections = listOf(ExecConnection(EXEC_EDGE, A, PortName("out"), B, PortName("in"))),
        dataConnections = listOf(DataConnection(DATA_EDGE, B, PortName("response"), C, PortName("text"))),
    )

    private companion object {
        val A = NodeId("a")
        val B = NodeId("b")
        val C = NodeId("c")

        const val EXEC_EDGE = "e1"
        const val DATA_EDGE = "d1"
    }
}
