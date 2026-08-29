package com.example.ottomatic.domain.registry

import com.example.ottomatic.core.model.NodeId
import com.example.ottomatic.core.model.NodeTypeId
import com.example.ottomatic.domain.model.DataConnection
import com.example.ottomatic.domain.model.ExecConnection
import com.example.ottomatic.domain.model.Workflow
import com.example.ottomatic.domain.model.WorkflowNode
import com.example.ottomatic.core.model.PortName
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A node whose type this build no longer declares is not drawn, so it can never be
 * selected or deleted — see [pruneUnknownNodes]. These pin what goes and, more
 * importantly, what stays.
 */
class UnknownNodePruneTest {

    @After
    fun tearDown() = PluginNodes.reset()

    private fun node(id: String, typeId: String) =
        WorkflowNode(id = NodeId(id), typeId = NodeTypeId(typeId), name = id, x = 0f, y = 0f)

    @Test
    fun `a graph of known nodes is returned untouched`() {
        val workflow = Workflow(
            nodes = listOf(node("n1", "trigger.manual"), node("n2", "action.delay")),
        )
        val result = pruneUnknownNodes(workflow)
        assertFalse(result.changed)
        assertSame(workflow, result.workflow)
    }

    @Test
    fun `a node of a type this build no longer declares is dropped`() {
        val result = pruneUnknownNodes(
            Workflow(nodes = listOf(node("n1", "trigger.manual"), node("ghost", "action.retired"))),
        )
        assertTrue(result.changed)
        assertEquals(listOf(NodeTypeId("action.retired")), result.removed)
        assertEquals(listOf(NodeId("n1")), result.workflow.nodes.map { it.id })
    }

    @Test
    fun `the edges that reached a dropped node go with it`() {
        val workflow = Workflow(
            nodes = listOf(
                node("n1", "trigger.manual"),
                node("ghost", "action.retired"),
                node("n2", "action.delay"),
            ),
            execConnections = listOf(
                ExecConnection("e1", NodeId("n1"), PortName("out"), NodeId("ghost"), PortName("in")),
                ExecConnection("e2", NodeId("ghost"), PortName("out"), NodeId("n2"), PortName("in")),
            ),
            dataConnections = listOf(
                DataConnection("d1", NodeId("ghost"), PortName("value"), NodeId("n2"), PortName("duration")),
            ),
        )
        val result = pruneUnknownNodes(workflow)
        assertTrue(result.workflow.execConnections.isEmpty())
        assertTrue(result.workflow.dataConnections.isEmpty())
    }

    @Test
    fun `an exec edge between two survivors is kept`() {
        val workflow = Workflow(
            nodes = listOf(node("n1", "trigger.manual"), node("n2", "action.delay"), node("ghost", "action.retired")),
            execConnections = listOf(
                ExecConnection("e1", NodeId("n1"), PortName("out"), NodeId("n2"), PortName("in")),
            ),
        )
        assertEquals(listOf("e1"), pruneUnknownNodes(workflow).workflow.execConnections.map { it.id })
    }

    /**
     * The case the whole prefix test exists for: a disabled or uninstalled plugin
     * looks exactly like a deleted one from [NodeTypeRegistry], and two of those three
     * states are undone by a switch in Settings.
     */
    @Test
    fun `a plugin node survives a registry that has never heard of it`() {
        val workflow = Workflow(nodes = listOf(node("p1", "plugin:com.acme.tools/do_thing")))
        assertFalse(pruneUnknownNodes(workflow).changed)
    }

    @Test
    fun `a plugin node survives a hydrated registry that no longer lists it`() {
        PluginNodes.hydrate(emptyList())
        val workflow = Workflow(nodes = listOf(node("p1", "plugin:com.acme.tools/do_thing")))
        assertFalse(pruneUnknownNodes(workflow).changed)
    }

    @Test
    fun `pruning twice changes nothing the second time`() {
        val once = pruneUnknownNodes(
            Workflow(nodes = listOf(node("n1", "trigger.manual"), node("ghost", "action.retired"))),
        ).workflow
        assertFalse(pruneUnknownNodes(once).changed)
    }
}
