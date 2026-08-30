package io.github.m1n1m1.easymatic.feature.grapheditor

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import io.github.m1n1m1.easymatic.core.model.ConfigKey
import io.github.m1n1m1.easymatic.core.model.NodeId
import io.github.m1n1m1.easymatic.core.model.NodeTypeId
import io.github.m1n1m1.easymatic.core.model.PortName
import io.github.m1n1m1.easymatic.domain.model.ExecConnection
import io.github.m1n1m1.easymatic.domain.model.Workflow
import io.github.m1n1m1.easymatic.domain.model.WorkflowNode
import io.github.m1n1m1.easymatic.domain.registry.BREAK_STRUCT_IN
import io.github.m1n1m1.easymatic.domain.registry.BREAK_TYPE_ID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a box drawn on the canvas takes with it.
 *
 * The capture rule is deliberately *intersection*, so several of these assert that
 * a box which only clips a node still takes it — that is the forgiving behaviour a
 * thumb-drawn box on a zoomed-out canvas needs.
 */
class MarqueeSelectionTest {

    @Test
    fun `a rect over a node captures it`() {
        val captured = marqueeCapture(workflow(), Rect(-10f, -10f, 250f, 100f))

        assertTrue(A in captured)
    }

    @Test
    fun `a rect clipping a node corner captures it`() {
        // The node spans (0,0)..(190,72); this box overlaps only its top-left.
        val captured = marqueeCapture(workflow(), Rect(-50f, -50f, 5f, 5f))

        assertTrue(A in captured)
    }

    @Test
    fun `a rect beside a node captures nothing`() {
        val captured = marqueeCapture(workflow(), Rect(-200f, -200f, -50f, -50f))

        assertTrue(captured.isEmpty)
    }

    @Test
    fun `a rect dragged up and to the left normalises`() {
        val dragged = Marquee(startGraph = Offset(250f, 100f), currentGraph = Offset(-10f, -10f))

        assertEquals(Rect(-10f, -10f, 250f, 100f), dragged.rect)
        assertTrue(A in marqueeCapture(workflow(), dragged.rect))
    }

    @Test
    fun `a rect over several nodes captures all of them`() {
        val captured = marqueeCapture(workflow(), Rect(-10f, -10f, 400f, 400f))

        assertEquals(setOf(A, B), captured.nodeIds)
    }

    @Test
    fun `a rect on a wire midpoint captures that connection`() {
        // A sits at y 0..72 and B at y 300, so this band is between the two cards
        // and can only be touching the wire that joins them.
        val captured = marqueeCapture(workflow(), Rect(0f, 150f, 200f, 200f))

        assertEquals(setOf(EDGE), captured.connectionIds)
        assertTrue("the band must miss both cards", captured.nodeIds.isEmpty())
    }

    @Test
    fun `a rect in the gap beside the wire captures nothing`() {
        val captured = marqueeCapture(workflow(), Rect(600f, 150f, 700f, 200f))

        assertTrue(captured.isEmpty)
    }

    @Test
    fun `a dynamic port node is bounded by its effective width`() {
        // action.break grows with the struct wired into it. Its declared width is
        // the minimum; using that instead of the effective one would leave the
        // right-hand part of the card outside its own bounds and unselectable.
        val wf = breakWorkflow()
        val node = wf.node(BREAK)!!
        val bounds = nodeBounds(wf, node)!!

        assertTrue(
            "expected a card wider than the minimum, got ${bounds.width}",
            bounds.width > GraphGeometry.NODE_MIN_WIDTH,
        )
        // A box touching only the far right of the card still has to take it.
        val farRight = Rect(bounds.right - 2f, bounds.top, bounds.right + 40f, bounds.bottom)
        assertTrue(BREAK in marqueeCapture(wf, farRight))
    }

    /** A -> B with a wide vertical gap, so a band between them can only hit the wire. */
    private fun workflow() = Workflow(
        nodes = listOf(
            WorkflowNode(A, NodeTypeId("trigger.manual"), "Manual", 0f, 0f),
            WorkflowNode(B, NodeTypeId("action.notify"), "Notify", 0f, 300f),
        ),
        execConnections = listOf(
            ExecConnection(EDGE, A, PortName("out"), B, PortName("in")),
        ),
    )

    /** A manual trigger feeding an `action.break`, which makes the break node grow. */
    private fun breakWorkflow(): Workflow {
        val trigger = WorkflowNode(A, NodeTypeId("trigger.charging"), "Charging", 0f, 0f)
        val breakNode = WorkflowNode(
            BREAK, BREAK_TYPE_ID, "Break", 0f, 200f,
            config = emptyMap<ConfigKey, String>(),
            visibleDataInputs = setOf(BREAK_STRUCT_IN),
        )
        return Workflow(
            nodes = listOf(trigger, breakNode),
            execConnections = listOf(
                ExecConnection(EDGE, A, PortName("out"), BREAK, PortName("in")),
            ),
            dataConnections = listOf(
                io.github.m1n1m1.easymatic.domain.model.DataConnection(
                    "d1", A, PortName("state"), BREAK, BREAK_STRUCT_IN,
                ),
            ),
        )
    }

    private companion object {
        val A = NodeId("a")
        val B = NodeId("b")
        val BREAK = NodeId("break")

        const val EDGE = "e1"
    }
}
