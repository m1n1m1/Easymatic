package com.example.ottomatic.engine

import com.example.ottomatic.core.model.ConfigKey
import com.example.ottomatic.core.model.NodeId
import com.example.ottomatic.core.model.NodeTypeId
import com.example.ottomatic.core.model.PortName
import com.example.ottomatic.domain.model.DataConnection
import com.example.ottomatic.domain.model.ExecConnection
import com.example.ottomatic.domain.model.PortKind
import com.example.ottomatic.domain.model.Workflow
import com.example.ottomatic.domain.model.WorkflowNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What it means to place a node and join two ports.
 *
 * These rules used to live inside `GraphEditorViewModel`, where the only thing that
 * could perform an edit was a finger. This file is what makes moving them safe: every
 * behaviour the editor relied on is asserted here rather than reached only through a
 * ViewModel that needs a `Context`.
 */
class GraphEditsTest {

    private fun node(id: String, typeId: String, config: Map<String, String> = emptyMap()) = WorkflowNode(
        id = NodeId(id),
        typeId = NodeTypeId(typeId),
        name = id,
        x = 0f,
        y = 0f,
        config = config.mapKeys { ConfigKey(it.key) },
    )

    private fun out(id: String, port: String, kind: PortKind = PortKind.DATA) =
        PortAddress(NodeId(id), PortName(port), kind, isOutput = true)

    private fun into(id: String, port: String, kind: PortKind = PortKind.DATA) =
        PortAddress(NodeId(id), PortName(port), kind, isOutput = false)

    // region Connecting

    @Test
    fun `an exec join lands on the exec channel and a data join on the data one`() {
        val workflow = Workflow(nodes = listOf(node("a", "trigger.manual"), node("b", "action.notify")))

        val execJoined = workflow
            .withConnection(out("a", "out", PortKind.EXECUTION), into("b", "in", PortKind.EXECUTION))
        assertEquals(1, execJoined.execConnections.size)
        assertTrue(execJoined.dataConnections.isEmpty())

        val dataJoined = workflow.withConnection(out("a", "x"), into("b", "text"))
        assertEquals(1, dataJoined.dataConnections.size)
        assertTrue(dataJoined.execConnections.isEmpty())
    }

    @Test
    fun `a duplicate join is visible to the caller rather than silently doubled`() {
        val output = out("a", "out", PortKind.EXECUTION)
        val input = into("b", "in", PortKind.EXECUTION)
        val workflow = Workflow(nodes = listOf(node("a", "trigger.manual"), node("b", "action.notify")))
            .withConnection(output, input)

        assertTrue(workflow.connectionExists(output, input))
        assertFalse(workflow.connectionExists(output, into("b", "elsewhere", PortKind.EXECUTION)))
    }

    @Test
    fun `a port is resolved through the placed node, not the bare declaration`() {
        val workflow = Workflow(nodes = listOf(node("battery", "value.battery")))
        assertNotNull(workflow.portAt(out("battery", "level")))
        assertNull("a port that does not exist must not resolve", workflow.portAt(out("battery", "charge")))
        assertNull("an unknown node must not resolve", workflow.portAt(out("ghost", "level")))
    }

    @Test
    fun `a convertible mismatch produces a Convert node wired on both sides`() {
        val workflow = Workflow(nodes = listOf(node("battery", "value.battery"), node("notify", "action.notify")))
        val output = out("battery", "level")
        val input = into("notify", "text")

        assertFalse("Int into Text must not type-check", workflow.isTypeCompatible(output, input))

        val autocast = workflow.withAutocast(output, input, place = { 5f to 6f }, nameOf = { it.displayName })
        assertNotNull(autocast)
        val convert = autocast!!.workflow.node(autocast.convertId)!!
        assertEquals("transform.convert", convert.typeId.value)
        assertEquals(5f to 6f, convert.x to convert.y)
        assertEquals("both halves of the wire", 2, autocast.workflow.dataConnections.size)
        assertTrue(
            "the Convert's own input starts hidden and would draw nowhere",
            convert.visibleDataInputs.any { it.value == "in" },
        )
    }

    @Test
    fun `a join with no conversion at all is refused`() {
        val workflow = Workflow(nodes = listOf(node("battery", "value.battery"), node("break", "action.break")))
        val autocast = workflow.withAutocast(
            output = out("battery", "level"),
            input = into("break", "struct"),
            place = { 0f to 0f },
            nameOf = { it.displayName },
        )
        assertNull("nothing converts to a struct", autocast)
    }

    @Test
    fun `revealing an input only touches data inputs`() {
        val workflow = Workflow(nodes = listOf(node("notify", "action.notify")))

        val revealed = workflow.withRevealedInput(into("notify", "text"))
        assertTrue(revealed.node(NodeId("notify"))!!.visibleDataInputs.any { it.value == "text" })

        val execUntouched = workflow.withRevealedInput(into("notify", "in", PortKind.EXECUTION))
        assertTrue(execUntouched.node(NodeId("notify"))!!.visibleDataInputs.isEmpty())

        val outputUntouched = workflow.withRevealedInput(out("notify", "text"))
        assertTrue(outputUntouched.node(NodeId("notify"))!!.visibleDataInputs.isEmpty())
    }

    // endregion

    // region Removing

    @Test
    fun `deleting a node takes both channels' edges with it`() {
        val workflow = Workflow(
            nodes = listOf(node("a", "trigger.manual"), node("b", "action.notify")),
            execConnections = listOf(
                ExecConnection("e", NodeId("a"), PortName("out"), NodeId("b"), PortName("in")),
            ),
            dataConnections = listOf(
                DataConnection("d", NodeId("a"), PortName("x"), NodeId("b"), PortName("text")),
            ),
        )
        val without = workflow.withoutNode(NodeId("b"))
        assertEquals(1, without.nodes.size)
        assertTrue(without.execConnections.isEmpty())
        assertTrue(without.dataConnections.isEmpty())
    }

    @Test
    fun `a wire is removed by id from whichever channel holds it`() {
        val workflow = Workflow(
            execConnections = listOf(
                ExecConnection("e", NodeId("a"), PortName("out"), NodeId("b"), PortName("in")),
            ),
            dataConnections = listOf(
                DataConnection("d", NodeId("a"), PortName("x"), NodeId("b"), PortName("text")),
            ),
        )
        assertTrue(workflow.withoutConnection("e").execConnections.isEmpty())
        assertEquals(1, workflow.withoutConnection("e").dataConnections.size)
        assertTrue(workflow.withoutConnection("d").dataConnections.isEmpty())
    }

    // endregion

    // region Config

    /**
     * `trigger.api` is callable the moment it is placed. An empty field would make the
     * commonest setup a two-step one, and the step nobody would guess at.
     */
    @Test
    fun `a generated config value is minted at placement`() {
        val token = initialConfigFor(NodeTypeId("trigger.api"))
        assertEquals(1, token.size)
        assertTrue(token.values.single().isNotBlank())
        assertTrue(initialConfigFor(NodeTypeId("action.notify")).isEmpty())
    }

    @Test
    fun `setting a value writes it, and an unknown node is left alone`() {
        val workflow = Workflow(nodes = listOf(node("notify", "action.notify")))
        val set = workflow.withConfig(NodeId("notify"), ConfigKey("title"), "Hello")
        assertEquals("Hello", set.node(NodeId("notify"))!!.config[ConfigKey("title")])
        assertEquals(workflow, workflow.withConfig(NodeId("ghost"), ConfigKey("title"), "Hello"))
    }

    /**
     * A `@Ports` edit can rename, delete or retype a port, and an edge left on one that
     * no longer exists draws, saves and silently carries nothing.
     */
    @Test
    fun `retyping a node's ports drops the edges that no longer fit`() {
        val workflow = Workflow(
            nodes = listOf(
                node("script", "action.script", mapOf("outputs" to "result:TEXT")),
                node("notify", "action.notify"),
            ),
            dataConnections = listOf(
                DataConnection("d", NodeId("script"), PortName("result"), NodeId("notify"), PortName("text")),
            ),
        )
        assertEquals(1, workflow.dataConnections.size)

        val renamed = workflow.withConfig(NodeId("script"), ConfigKey("outputs"), "answer:TEXT")
        assertTrue("the edge points at a port that no longer exists", renamed.dataConnections.isEmpty())
    }

    /**
     * Clearing a scoping field back to blank means "any", under which what it scoped is
     * still coherent. Blanking it there would be gratuitous.
     */
    @Test
    fun `clearing a value scopes nothing away`() {
        val workflow = Workflow(nodes = listOf(node("notify", "action.notify", mapOf("title" to "Hi"))))
        val cleared = workflow.withConfig(NodeId("notify"), ConfigKey("title"), "")
        assertEquals("", cleared.node(NodeId("notify"))!!.config[ConfigKey("title")])
    }

    // endregion
}
