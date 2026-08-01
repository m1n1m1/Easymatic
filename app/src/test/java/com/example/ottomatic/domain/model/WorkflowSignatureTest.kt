package com.example.ottomatic.domain.model

import com.example.ottomatic.core.model.ConfigKey
import com.example.ottomatic.core.model.NodeId
import com.example.ottomatic.core.model.NodeTypeId
import com.example.ottomatic.core.model.PortName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * [Workflow.runtimeSignature] decides whether a save in the graph editor re-arms
 * the running macro. It must be blind to cosmetic edits — otherwise dragging a
 * node would re-register every geofence in the workflow — and sensitive to
 * anything the engine reads.
 */
class WorkflowSignatureTest {

    /**
     * A node with everything cosmetic at its default; tests vary the cosmetic
     * fields with [WorkflowNode.copy] so this stays a short parameter list.
     */
    private fun node(
        id: String = "n1",
        typeId: String = "trigger.manual",
        config: Map<ConfigKey, String> = emptyMap(),
    ) = WorkflowNode(NodeId(id), NodeTypeId(typeId), name = "Manual", x = 0f, y = 0f, config = config)

    private fun workflow(vararg nodes: WorkflowNode) =
        Workflow(id = "w1", name = "W", nodes = nodes.toList())

    // region Cosmetic edits must not re-arm

    @Test
    fun `moving a node does not change the signature`() {
        val before = workflow(node())
        val after = workflow(node().copy(x = 640f, y = 480f))
        assertEquals(before.runtimeSignature(), after.runtimeSignature())
    }

    @Test
    fun `a group move does not change the signature`() {
        // Dragging a multi-selection moves every node in it at once. That must
        // stay as free as moving one: re-arming re-registers geofences and
        // re-enqueues periodic work, and none of it depends on where a card sits.
        val before = workflow(node(id = "n1"), node(id = "n2"), node(id = "n3"))
        val after = workflow(
            node(id = "n1").copy(x = 40f, y = 40f),
            node(id = "n2").copy(x = 40f, y = 40f),
            node(id = "n3").copy(x = 40f, y = 40f),
        )
        assertEquals(before.runtimeSignature(), after.runtimeSignature())
    }

    @Test
    fun `renaming a node does not change the signature`() {
        val before = workflow(node())
        val after = workflow(node().copy(name = "Start of my macro"))
        assertEquals(before.runtimeSignature(), after.runtimeSignature())
    }

    @Test
    fun `toggling data input visibility does not change the signature`() {
        val before = workflow(node())
        val after = workflow(node().copy(visibleDataInputs = setOf(PortName("text"))))
        assertEquals(before.runtimeSignature(), after.runtimeSignature())
    }

    @Test
    fun `renaming the workflow does not change the signature`() {
        val before = workflow(node())
        val after = before.copy(name = "Renamed")
        assertEquals(before.runtimeSignature(), after.runtimeSignature())
    }

    @Test
    fun `an unchanged workflow has an equal signature`() {
        val w = workflow(node(config = mapOf(ConfigKey("text") to "hello")))
        assertEquals(w.runtimeSignature(), w.copy().runtimeSignature())
    }

    // endregion

    // region Execution-relevant edits must re-arm

    @Test
    fun `changing a config value changes the signature`() {
        val before = workflow(node(config = mapOf(ConfigKey("text") to "A")))
        val after = workflow(node(config = mapOf(ConfigKey("text") to "B")))
        assertNotEquals(before.runtimeSignature(), after.runtimeSignature())
    }

    @Test
    fun `adding a node changes the signature`() {
        val before = workflow(node())
        val after = workflow(node(), node(id = "n2", typeId = "action.notify"))
        assertNotEquals(before.runtimeSignature(), after.runtimeSignature())
    }

    @Test
    fun `deleting a node changes the signature`() {
        val before = workflow(node(), node(id = "n2", typeId = "action.notify"))
        val after = workflow(node())
        assertNotEquals(before.runtimeSignature(), after.runtimeSignature())
    }

    @Test
    fun `changing a node type changes the signature`() {
        val before = workflow(node(typeId = "trigger.manual"))
        val after = workflow(node(typeId = "trigger.boot"))
        assertNotEquals(before.runtimeSignature(), after.runtimeSignature())
    }

    @Test
    fun `adding an exec connection changes the signature`() {
        val before = workflow(node(), node(id = "n2", typeId = "action.notify"))
        val after = before.copy(
            execConnections = listOf(
                ExecConnection("e1", NodeId("n1"), PortName("out"), NodeId("n2"), PortName("in")),
            ),
        )
        assertNotEquals(before.runtimeSignature(), after.runtimeSignature())
    }

    @Test
    fun `adding a data connection changes the signature`() {
        val before = workflow(node(), node(id = "n2", typeId = "action.notify"))
        val after = before.copy(
            dataConnections = listOf(
                DataConnection("d1", NodeId("n1"), PortName("value"), NodeId("n2"), PortName("text")),
            ),
        )
        assertNotEquals(before.runtimeSignature(), after.runtimeSignature())
    }

    @Test
    fun `toggling enabled changes the signature`() {
        val before = workflow(node())
        assertNotEquals(before.runtimeSignature(), before.copy(enabled = true).runtimeSignature())
    }

    // endregion
}
