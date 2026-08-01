package com.example.ottomatic.feature.grapheditor

import androidx.compose.ui.geometry.Offset
import com.example.ottomatic.core.model.NodeId
import com.example.ottomatic.core.model.NodeTypeId
import com.example.ottomatic.domain.model.Workflow
import com.example.ottomatic.domain.model.WorkflowNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The behavioural core of the canvas gestures.
 *
 * These reducers exist as pure functions precisely so this file can exist: the
 * project's only test dependency is junit, so a `GraphEditorViewModel` cannot be
 * built in a unit test at all. Every rule worth pinning down — what a tap does in
 * each mode, and what a cancelled gesture restores — lives here rather than in the
 * ViewModel.
 */
class GraphInteractionReducerTest {

    // region Taps

    @Test
    fun `a tap replaces the selection when multi-select is off`() {
        val after = state(selection = Selection.ofNode(A)).withTappedNode(B)

        assertEquals(setOf(B), after.selection.nodeIds)
    }

    @Test
    fun `a tap adds to the selection when multi-select is on`() {
        val after = state(selection = Selection.ofNode(A), multiSelect = true).withTappedNode(B)

        assertEquals(setOf(A, B), after.selection.nodeIds)
    }

    @Test
    fun `a tap removes an already selected node when multi-select is on`() {
        val after = state(selection = Selection(nodeIds = setOf(A, B)), multiSelect = true)
            .withTappedNode(A)

        assertEquals(setOf(B), after.selection.nodeIds)
    }

    @Test
    fun `tapping the last selected node off also leaves multi-select`() {
        // Otherwise the mode outlives its selection and the next tap toggles
        // instead of selecting, with nothing on screen explaining why.
        val after = state(selection = Selection.ofNode(A), multiSelect = true).withTappedNode(A)

        assertTrue(after.selection.isEmpty)
        assertFalse(after.interaction.isMultiSelect)
    }

    @Test
    fun `a tap on an edge follows the same replace-or-toggle rule`() {
        val replaced = state(selection = Selection.ofNode(A)).withTappedConnection(EDGE)
        assertEquals(Selection.ofConnection(EDGE), replaced.selection)

        val added = state(selection = Selection.ofNode(A), multiSelect = true).withTappedConnection(EDGE)
        assertEquals(setOf(A), added.selection.nodeIds)
        assertEquals(setOf(EDGE), added.selection.connectionIds)
    }

    @Test
    fun `tapping empty canvas clears the selection and leaves multi-select`() {
        val after = state(selection = Selection(nodeIds = setOf(A, B)), multiSelect = true)
            .withClearedSelection()

        assertTrue(after.selection.isEmpty)
        assertFalse(after.interaction.isMultiSelect)
    }

    @Test
    fun `a long press turns multi-select on and joins the node`() {
        val after = state(selection = Selection.ofNode(A)).withLongPressedNode(B)

        assertTrue(after.interaction.isMultiSelect)
        assertEquals(setOf(A, B), after.selection.nodeIds)
    }

    @Test
    fun `a long press on an already selected node keeps it selected`() {
        // Entering the mode by removing the node you pressed would be nonsense.
        val after = state(selection = Selection.ofNode(A), multiSelect = true).withLongPressedNode(A)

        assertEquals(setOf(A), after.selection.nodeIds)
        assertTrue(after.interaction.isMultiSelect)
    }

    // endregion

    // region Node gesture

    @Test
    fun `a press alone changes nothing visible`() {
        val before = state(selection = Selection.ofNode(A))
        val after = before.withNodeGestureBegun()

        assertEquals(before.selection, after.selection)
        assertEquals(before.workflow, after.workflow)
    }

    @Test
    fun `starting a drag on an unselected node grabs it`() {
        val after = state(selection = Selection.ofNode(A))
            .withNodeGestureBegun()
            .withNodeDragBegun(B)

        assertEquals(setOf(B), after.selection.nodeIds)
    }

    @Test
    fun `starting a drag inside a selection keeps the whole selection`() {
        val after = state(selection = Selection(nodeIds = setOf(A, B)), multiSelect = true)
            .withNodeGestureBegun()
            .withNodeDragBegun(A)

        assertEquals(setOf(A, B), after.selection.nodeIds)
        assertEquals(setOf(A, B), after.interaction.nodeDrag?.dragNodeIds)
    }

    @Test
    fun `a drag moves every node in the drag set`() {
        val after = state(selection = Selection(nodeIds = setOf(A, B)), multiSelect = true)
            .withNodeGestureBegun()
            .withNodeDragBegun(A)
            .withDragDelta(Offset(10f, 10f))

        assertEquals(Offset(10f, 10f), after.positionOf(A))
        assertEquals(Offset(110f, 110f), after.positionOf(B))
        assertEquals(Offset(200f, 200f), after.positionOf(C))
    }

    @Test
    fun `a finished drag leaves the moved positions in place`() {
        val after = state()
            .withNodeGestureBegun()
            .withNodeDragBegun(A)
            .withDragDelta(Offset(10f, 10f))
            .withNodeGestureEnded()

        assertEquals(Offset(10f, 10f), after.positionOf(A))
        assertEquals(null, after.interaction.nodeDrag)
    }

    @Test
    fun `only a drag that moved something is worth saving`() {
        val pressed = state().withNodeGestureBegun()
        assertFalse(pressed.hasUnsavedNodeMove)

        val dragged = pressed.withNodeDragBegun(A).withDragDelta(Offset(1f, 0f))
        assertTrue(dragged.hasUnsavedNodeMove)
    }

    @Test
    fun `ending the gesture after a tap disarms the undo point`() {
        // A tap opens an undo point like any other press. If it were left armed,
        // the next gesture to be cancelled — a pinch, say — would roll the
        // selection back to before the tap.
        val after = state(selection = Selection.ofNode(A))
            .withNodeGestureBegun()
            .withTappedNode(B)
            .withNodeGestureEnded()

        assertEquals(setOf(B), after.withNodeGestureReverted().selection.nodeIds)
    }

    @Test
    fun `a cancelled drag restores every dragged node to its origin`() {
        val before = state(selection = Selection(nodeIds = setOf(A, B)), multiSelect = true)
        val after = before
            .withNodeGestureBegun()
            .withNodeDragBegun(A)
            .withDragDelta(Offset(10f, 10f))
            .withDragDelta(Offset(20f, 20f))
            .withNodeGestureReverted()

        assertEquals(before.workflow.nodes, after.workflow.nodes)
    }

    @Test
    fun `a cancelled gesture restores the pre-press selection`() {
        val after = state(selection = Selection.ofNode(A))
            .withNodeGestureBegun()
            .withNodeDragBegun(B)
            .withNodeGestureReverted()

        assertEquals(setOf(A), after.selection.nodeIds)
    }

    @Test
    fun `a cancelled gesture undoes a long press that had joined the node`() {
        // The whole point of snapshotting on press: a pinch that starts on a node
        // must leave the graph exactly as the user last saw it, selection included.
        val after = state(selection = Selection.ofNode(A))
            .withNodeGestureBegun()
            .withLongPressedNode(B)
            .withNodeGestureReverted()

        assertEquals(setOf(A), after.selection.nodeIds)
        assertFalse(after.interaction.isMultiSelect)
    }

    @Test
    fun `reverting twice is harmless`() {
        // The gate cancels on the Initial pass and the node's own drag loop
        // cancels again on the next Main pass, so this happens on every takeover.
        val once = state()
            .withNodeGestureBegun()
            .withNodeDragBegun(A)
            .withDragDelta(Offset(10f, 10f))
            .withNodeGestureReverted()

        assertEquals(once, once.withNodeGestureReverted())
    }

    @Test
    fun `a drag delta with no gesture in flight is ignored`() {
        val before = state()

        assertEquals(before, before.withDragDelta(Offset(10f, 10f)))
    }

    // endregion

    private fun GraphEditorUiState.positionOf(id: NodeId): Offset =
        workflow.node(id)!!.let { Offset(it.x, it.y) }

    private fun state(
        selection: Selection = Selection.EMPTY,
        multiSelect: Boolean = false,
    ) = GraphEditorUiState(
        workflow = Workflow(
            nodes = listOf(
                WorkflowNode(A, NodeTypeId("trigger.manual"), "Manual", 0f, 0f),
                WorkflowNode(B, NodeTypeId("action.http"), "HTTP", 100f, 100f),
                WorkflowNode(C, NodeTypeId("action.notify"), "Notify", 200f, 200f),
            ),
        ),
        selection = selection,
        interaction = GraphInteraction(isMultiSelect = multiSelect),
    )

    private companion object {
        val A = NodeId("a")
        val B = NodeId("b")
        val C = NodeId("c")

        const val EDGE = "e1"
    }
}
