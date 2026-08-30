package io.github.m1n1m1.easymatic.feature.grapheditor

import io.github.m1n1m1.easymatic.core.model.NodeId
import io.github.m1n1m1.easymatic.core.model.NodeTypeId
import io.github.m1n1m1.easymatic.core.model.PortName
import io.github.m1n1m1.easymatic.domain.model.DataConnection
import io.github.m1n1m1.easymatic.domain.model.ExecConnection
import io.github.m1n1m1.easymatic.domain.model.Workflow
import io.github.m1n1m1.easymatic.domain.model.WorkflowNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The selection is a set covering nodes *and* edges, so the interesting cases are
 * the ones where the two kinds interact: what "the one node to configure" means
 * when an edge is also selected, and what deleting a mixed selection leaves behind.
 */
class SelectionOpsTest {

    @Test
    fun `toggling an unselected node adds it`() {
        val selection = Selection.ofNode(A).toggleNode(B)

        assertEquals(setOf(A, B), selection.nodeIds)
    }

    @Test
    fun `toggling a selected node removes it`() {
        val selection = Selection(nodeIds = setOf(A, B)).toggleNode(A)

        assertEquals(setOf(B), selection.nodeIds)
    }

    @Test
    fun `toggling away the last member empties the selection`() {
        assertTrue(Selection.ofNode(A).toggleNode(A).isEmpty)
    }

    @Test
    fun `a lone node is configurable`() {
        assertEquals(A, Selection.ofNode(A).singleNodeId)
    }

    @Test
    fun `singleNodeId is null for several nodes`() {
        assertNull(Selection(nodeIds = setOf(A, B)).singleNodeId)
    }

    @Test
    fun `singleNodeId is null when an edge is also selected`() {
        // The config sheet edits one node's fields; an edge alongside it means the
        // Configure button would open a sheet that does not represent the selection.
        assertNull(Selection(nodeIds = setOf(A), connectionIds = setOf(EXEC_EDGE)).singleNodeId)
    }

    @Test
    fun `withoutSelection removes every edge incident to a deleted node`() {
        val result = workflow().withoutSelection(Selection.ofNode(B))

        assertTrue("node B should be gone", result.nodes.none { it.id == B })
        // B sits between A and C, so both of its edges have to go with it.
        assertTrue("A->B should be gone", result.execConnections.none { it.id == EXEC_EDGE })
        assertTrue("B->C data edge should be gone", result.dataConnections.isEmpty())
    }

    @Test
    fun `withoutSelection leaves unrelated nodes and edges alone`() {
        val result = workflow().withoutSelection(Selection.ofConnection(DATA_EDGE))

        assertEquals(3, result.nodes.size)
        assertEquals(listOf(EXEC_EDGE), result.execConnections.map { it.id })
        assertTrue(result.dataConnections.isEmpty())
    }

    @Test
    fun `withoutSelection removes a mixed node and edge selection in one pass`() {
        val result = workflow()
            .withoutSelection(Selection(nodeIds = setOf(A), connectionIds = setOf(DATA_EDGE)))

        assertEquals(setOf(B, C), result.nodes.mapTo(HashSet()) { it.id })
        assertTrue(result.execConnections.isEmpty())
        assertTrue(result.dataConnections.isEmpty())
    }

    @Test
    fun `an empty selection deletes nothing`() {
        val before = workflow()

        assertEquals(before, before.withoutSelection(Selection.EMPTY))
    }

    @Test
    fun `contains answers for both kinds`() {
        val selection = Selection(nodeIds = setOf(A), connectionIds = setOf(EXEC_EDGE))

        assertTrue(A in selection)
        assertFalse(B in selection)
        assertTrue(EXEC_EDGE in selection)
        assertFalse(DATA_EDGE in selection)
    }

    @Test
    fun `summary names one node`() {
        assertEquals(SelectionSummary.Nodes(1), selectionSummary(Selection.ofNode(A)))
    }

    @Test
    fun `summary counts several nodes`() {
        assertEquals(SelectionSummary.Nodes(2), selectionSummary(Selection(nodeIds = setOf(A, B))))
    }

    @Test
    fun `summary names connections when no node is selected`() {
        assertEquals(SelectionSummary.Connections(1), selectionSummary(Selection.ofConnection(EXEC_EDGE)))
        assertEquals(
            SelectionSummary.Connections(2),
            selectionSummary(Selection(connectionIds = setOf(EXEC_EDGE, DATA_EDGE))),
        )
    }

    @Test
    fun `summary counts a mixed selection by kind`() {
        // Not "3 selected": the delete that follows is the one action the user
        // cannot undo, so the bar says exactly what is about to go.
        val selection = Selection(nodeIds = setOf(A, B), connectionIds = setOf(EXEC_EDGE))

        assertEquals(SelectionSummary.Mixed(nodes = 2, connections = 1), selectionSummary(selection))
    }

    @Test
    fun `summary has something to say about an empty selection`() {
        // Unreachable from the bar, which shows the workflow mode instead — but a
        // classifier that answered Nodes(0) would be a trap for the next caller.
        assertEquals(SelectionSummary.Empty, selectionSummary(Selection.EMPTY))
    }

    /** A -> B (exec) and B -> C (data), so deleting B orphans one edge of each kind. */
    private fun workflow() = Workflow(
        nodes = listOf(
            WorkflowNode(A, NodeTypeId("trigger.manual"), "Manual", 0f, 0f),
            WorkflowNode(B, NodeTypeId("action.http"), "HTTP", 0f, 100f),
            WorkflowNode(C, NodeTypeId("action.notify"), "Notify", 0f, 200f),
        ),
        execConnections = listOf(
            ExecConnection(EXEC_EDGE, A, PortName("out"), B, PortName("in")),
        ),
        dataConnections = listOf(
            DataConnection(DATA_EDGE, B, PortName("response"), C, PortName("text")),
        ),
    )

    private companion object {
        val A = NodeId("a")
        val B = NodeId("b")
        val C = NodeId("c")

        const val EXEC_EDGE = "e1"
        const val DATA_EDGE = "d1"
    }
}
