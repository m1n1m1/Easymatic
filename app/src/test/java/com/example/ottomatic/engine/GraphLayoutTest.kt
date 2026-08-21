package com.example.ottomatic.engine

import com.example.ottomatic.core.model.NodeId
import com.example.ottomatic.core.model.NodeTypeId
import com.example.ottomatic.core.model.PortName
import com.example.ottomatic.domain.model.ExecConnection
import com.example.ottomatic.domain.model.Workflow
import com.example.ottomatic.domain.model.WorkflowNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where the assistant's nodes end up.
 *
 * The rule this file exists to hold is the second one: **a macro the user arranged by
 * hand is theirs**. Adding two nodes to it must not reflow the twenty that were already
 * where they wanted them, and a layout that quietly did would be discovered only by
 * somebody whose work had already moved.
 */
class GraphLayoutTest {

    private fun node(id: String, typeId: String, x: Float = 0f, y: Float = 0f) = WorkflowNode(
        id = NodeId(id),
        typeId = NodeTypeId(typeId),
        name = id,
        x = x,
        y = y,
    )

    private fun exec(from: String, to: String) = ExecConnection(
        id = "$from-$to",
        fromNodeId = NodeId(from),
        fromPort = PortName("out"),
        toNodeId = NodeId(to),
        toPort = PortName("in"),
    )

    private fun Workflow.at(id: String): Pair<Float, Float> = node(NodeId(id))!!.let { it.x to it.y }

    @Test
    fun `nodes the user placed are never moved`() {
        val workflow = Workflow(
            nodes = listOf(
                node("mine", "trigger.manual", x = 123f, y = 456f),
                node("new", "action.notify"),
            ),
        )
        val arranged = GraphLayout.arrange(workflow, setOf(NodeId("new")))
        assertEquals(123f to 456f, arranged.at("mine"))
    }

    @Test
    fun `an exec chain lays out top to bottom`() {
        val workflow = Workflow(
            nodes = listOf(
                node("a", "trigger.manual"),
                node("b", "action.notify"),
                node("c", "action.notify"),
            ),
            execConnections = listOf(exec("a", "b"), exec("b", "c")),
        )
        val arranged = GraphLayout.arrange(workflow, workflow.nodes.map { it.id }.toSet())

        assertTrue(arranged.at("a").second < arranged.at("b").second)
        assertTrue(arranged.at("b").second < arranged.at("c").second)
        assertEquals(arranged.at("a").first, arranged.at("b").first, 0f)
    }

    /**
     * Longest path rather than shortest, so a join lands below **both** of its branches
     * rather than overlapping the shorter one.
     */
    @Test
    fun `a branch spreads sideways and its join lands below both arms`() {
        val workflow = Workflow(
            nodes = listOf(
                node("if", "action.if"),
                node("left", "action.notify"),
                node("right", "action.notify"),
                node("join", "action.notify"),
            ),
            execConnections = listOf(
                exec("if", "left"),
                exec("if", "right"),
                exec("left", "join"),
                exec("right", "join"),
            ),
        )
        val arranged = GraphLayout.arrange(workflow, workflow.nodes.map { it.id }.toSet())

        assertEquals("the two arms are the same layer", arranged.at("left").second, arranged.at("right").second, 0f)
        assertTrue("the arms must not overlap", arranged.at("left").first != arranged.at("right").first)
        assertTrue(arranged.at("join").second > arranged.at("left").second)
    }

    /** A value has no exec position at all: it belongs beside whatever reads it. */
    @Test
    fun `a pull-side node is placed beside its consumer`() {
        val workflow = Workflow(
            nodes = listOf(
                node("trigger", "trigger.manual"),
                node("notify", "action.notify"),
                node("battery", "value.battery"),
            ),
            execConnections = listOf(exec("trigger", "notify")),
            dataConnections = listOf(
                com.example.ottomatic.domain.model.DataConnection(
                    id = "d",
                    fromNodeId = NodeId("battery"),
                    fromPort = PortName("level"),
                    toNodeId = NodeId("notify"),
                    toPort = PortName("progress"),
                ),
            ),
        )
        val arranged = GraphLayout.arrange(workflow, workflow.nodes.map { it.id }.toSet())

        assertTrue("should sit left of its consumer", arranged.at("battery").first < arranged.at("notify").first)
        assertTrue("should sit above its consumer", arranged.at("battery").second < arranged.at("notify").second)
    }

    /**
     * A graph the validator has not seen yet may still contain an exec cycle. A layout
     * that hangs on one is worse than a layout that flattens it.
     */
    @Test
    fun `an exec cycle flattens rather than hanging`() {
        val workflow = Workflow(
            nodes = listOf(node("a", "action.notify"), node("b", "action.notify")),
            execConnections = listOf(exec("a", "b"), exec("b", "a")),
        )
        val arranged = GraphLayout.arrange(workflow, workflow.nodes.map { it.id }.toSet())
        assertEquals(2, arranged.nodes.size)
    }

    @Test
    fun `new nodes are anchored below whatever was already on the canvas`() {
        val workflow = Workflow(
            nodes = listOf(
                node("mine", "trigger.manual", x = 0f, y = 1000f),
                node("new", "action.notify"),
            ),
        )
        val arranged = GraphLayout.arrange(workflow, setOf(NodeId("new")))
        assertTrue("would otherwise land on top of existing work", arranged.at("new").second > 1000f)
    }

    /** Each provisional placement steps down, so live edits appear one at a time. */
    @Test
    fun `the staging column steps down as nodes are added`() {
        var workflow = Workflow()
        val first = GraphLayout.provisionalPosition(workflow)
        workflow = workflow.copy(nodes = listOf(node("a", "action.notify", first.first, first.second)))
        val second = GraphLayout.provisionalPosition(workflow)
        assertTrue(second.second > first.second)
    }

    @Test
    fun `arranging nothing changes nothing`() {
        val workflow = Workflow(nodes = listOf(node("a", "action.notify", x = 7f, y = 9f)))
        assertEquals(workflow, GraphLayout.arrange(workflow, emptySet()))
    }
}
