package io.github.m1n1m1.easymatic.feature.grapheditor

import androidx.compose.ui.geometry.Offset
import io.github.m1n1m1.easymatic.core.model.NodeId
import io.github.m1n1m1.easymatic.core.model.NodeTypeId
import io.github.m1n1m1.easymatic.domain.model.Workflow
import io.github.m1n1m1.easymatic.domain.model.WorkflowNode
import org.junit.Assert.assertEquals
import org.junit.Test

/** The graph maths behind a group move and behind undoing one. */
class NodeDragSessionTest {

    @Test
    fun `dragging a node inside the selection drags the whole selection`() {
        val selection = Selection(nodeIds = setOf(A, B))

        assertEquals(setOf(A, B), dragSetFor(selection, A))
    }

    @Test
    fun `dragging a node outside the selection drags only that node`() {
        // How you move something without first dismissing a multi-selection.
        val selection = Selection(nodeIds = setOf(A, B))

        assertEquals(setOf(C), dragSetFor(selection, C))
    }

    @Test
    fun `dragging with nothing selected drags only that node`() {
        assertEquals(setOf(A), dragSetFor(Selection.EMPTY, A))
    }

    @Test
    fun `every dragged node shifts by the same delta`() {
        val moved = workflow().movedBy(setOf(A, B), Offset(10f, -5f))

        assertEquals(Offset(10f, -5f), moved.positionOf(A))
        assertEquals(Offset(110f, 95f), moved.positionOf(B))
    }

    @Test
    fun `nodes outside the drag set do not move`() {
        val moved = workflow().movedBy(setOf(A), Offset(10f, -5f))

        assertEquals(Offset(200f, 200f), moved.positionOf(C))
    }

    @Test
    fun `restoring origin positions undoes an accumulated drag`() {
        val before = workflow()
        val origins = before.positionsOf(setOf(A, B))

        // Several frames, as a real drag arrives.
        val dragged = before
            .movedBy(setOf(A, B), Offset(3f, 3f))
            .movedBy(setOf(A, B), Offset(4f, 4f))
            .movedBy(setOf(A, B), Offset(5f, 5f))

        assertEquals(before.nodes, dragged.withNodePositions(origins).nodes)
    }

    @Test
    fun `a zero delta leaves the graph identical`() {
        val before = workflow()

        assertEquals(before, before.movedBy(setOf(A), Offset.Zero))
    }

    private fun Workflow.positionOf(id: NodeId): Offset =
        node(id)!!.let { Offset(it.x, it.y) }

    private fun workflow() = Workflow(
        nodes = listOf(
            WorkflowNode(A, NodeTypeId("trigger.manual"), "Manual", 0f, 0f),
            WorkflowNode(B, NodeTypeId("action.http"), "HTTP", 100f, 100f),
            WorkflowNode(C, NodeTypeId("action.notify"), "Notify", 200f, 200f),
        ),
    )

    private companion object {
        val A = NodeId("a")
        val B = NodeId("b")
        val C = NodeId("c")
    }
}
