package io.github.m1n1m1.easymatic.feature.grapheditor

import androidx.compose.ui.geometry.Offset
import io.github.m1n1m1.easymatic.core.model.NodeId
import io.github.m1n1m1.easymatic.core.model.NodeTypeId
import io.github.m1n1m1.easymatic.domain.model.Workflow
import io.github.m1n1m1.easymatic.domain.model.WorkflowNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** How a box selection turns into a selection, and what happens when it does not. */
class MarqueeReducerTest {

    @Test
    fun `a marquee tracks what it would capture while it is dragged`() {
        val state = state()
            .withMarqueeStarted(Offset(-10f, -10f))
            .withMarqueeMoved(Offset(250f, 100f))

        assertEquals(setOf(A), state.interaction.marquee?.captured?.nodeIds)
    }

    @Test
    fun `committing a marquee selects everything it touched and enables multi-select`() {
        // Multi-select goes on even for one node, so the taps that follow refine
        // the box instead of throwing it away.
        val state = state()
            .withMarqueeStarted(Offset(-10f, -10f))
            .withMarqueeMoved(Offset(250f, 100f))
            .withMarqueeCommitted()

        assertEquals(setOf(A), state.selection.nodeIds)
        assertTrue(state.interaction.isMultiSelect)
        assertNull(state.interaction.marquee)
    }

    @Test
    fun `a marquee replaces rather than merges with the existing selection`() {
        val state = state(selection = Selection.ofNode(C), multiSelect = true)
            .withMarqueeStarted(Offset(-10f, -10f))
            .withMarqueeMoved(Offset(250f, 100f))
            .withMarqueeCommitted()

        assertEquals(setOf(A), state.selection.nodeIds)
    }

    @Test
    fun `a marquee that caught nothing clears the selection`() {
        val state = state(selection = Selection.ofNode(A), multiSelect = true)
            .withMarqueeStarted(Offset(-500f, -500f))
            .withMarqueeMoved(Offset(-400f, -400f))
            .withMarqueeCommitted()

        assertTrue(state.selection.isEmpty)
        assertFalse(state.interaction.isMultiSelect)
    }

    @Test
    fun `cancelling a marquee leaves the selection untouched`() {
        val before = state(selection = Selection.ofNode(C))
        val after = before
            .withMarqueeStarted(Offset(-10f, -10f))
            .withMarqueeMoved(Offset(250f, 100f))
            .withMarqueeCancelled()

        assertEquals(before.selection, after.selection)
        assertNull(after.interaction.marquee)
    }

    @Test
    fun `cancelling with no marquee in flight is harmless`() {
        // The two-finger gate cancels unconditionally on every takeover.
        val before = state()

        assertEquals(before, before.withMarqueeCancelled())
    }

    @Test
    fun `a move with no marquee in flight is ignored`() {
        val before = state()

        assertEquals(before, before.withMarqueeMoved(Offset(10f, 10f)))
    }

    private fun state(
        selection: Selection = Selection.EMPTY,
        multiSelect: Boolean = false,
    ) = GraphEditorUiState(
        workflow = Workflow(
            nodes = listOf(
                WorkflowNode(A, NodeTypeId("trigger.manual"), "Manual", 0f, 0f),
                WorkflowNode(C, NodeTypeId("action.notify"), "Notify", 0f, 600f),
            ),
        ),
        selection = selection,
        interaction = GraphInteraction(isMultiSelect = multiSelect),
    )

    private companion object {
        val A = NodeId("a")
        val C = NodeId("c")
    }
}
