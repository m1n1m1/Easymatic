package com.example.ottomatic.domain.registry

import com.example.ottomatic.core.model.ConfigKey
import com.example.ottomatic.core.model.NodeId
import com.example.ottomatic.core.model.NodeTypeId
import com.example.ottomatic.domain.model.DataConnection
import com.example.ottomatic.domain.model.Direction
import com.example.ottomatic.domain.model.ExecPorts
import com.example.ottomatic.domain.model.PortKind
import com.example.ottomatic.domain.model.Workflow
import com.example.ottomatic.domain.model.WorkflowNode
import com.example.ottomatic.domain.model.config.ComparisonType
import com.example.ottomatic.domain.model.schema.ItemSchema
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two things a dialog node's ports depend on its own config for.
 *
 * Both are the same idea `action.script` states: a port exists once it has been
 * asked for. A `timed_out` branch on a dialog that waits forever can never fire,
 * and an answer port that claims to be text when the node is asking for a number
 * would refuse the wire that actually fits.
 */
class DialogPortsTest {

    // region The timeout branch

    @Test
    fun `a dialog that waits forever shows no timed-out branch`() {
        for (typeId in DIALOG_TYPE_IDS) {
            assertFalse(
                "$typeId should hide its timeout branch at 0",
                portNames(typeId, timeout = "0").contains(ExecPorts.TIMED_OUT),
            )
        }
    }

    @Test
    fun `configuring a timeout reveals the branch`() {
        for (typeId in DIALOG_TYPE_IDS) {
            assertTrue(
                "$typeId should offer a timeout branch at 60",
                portNames(typeId, timeout = "60").contains(ExecPorts.TIMED_OUT),
            )
        }
    }

    @Test
    fun `an unconfigured node hides it too`() {
        // A blank config value is the state of every node straight out of the
        // palette, and it must read the same as an explicit zero.
        assertFalse(portNames(DIALOG_CONFIRM_TYPE_ID, timeout = null).contains(ExecPorts.TIMED_OUT))
    }

    @Test
    fun `the route stays declared even while its port is hidden`() {
        // `routePort`'s require() checks the *declaration*, so hiding the handle
        // must not be able to turn a timeout into a crash.
        val declared = NodeTypeRegistry.byId(DIALOG_CONFIRM_TYPE_ID)!!.ports
        assertTrue(declared.any { it.kind == PortKind.EXECUTION && it.name == ExecPorts.TIMED_OUT })
    }

    @Test
    fun `the decision branches are labelled, not left as bare port names`() {
        val ports = NodeTypeRegistry.byId(DIALOG_CONFIRM_TYPE_ID)!!.ports.associateBy { it.name }
        assertEquals(ExecPorts.CONFIRMED_LABEL, ports[ExecPorts.CONFIRMED]?.label)
        assertEquals(ExecPorts.CANCELLED_LABEL, ports[ExecPorts.CANCELLED]?.label)
        assertEquals(ExecPorts.TIMED_OUT_LABEL, ports[ExecPorts.TIMED_OUT]?.label)
    }

    // endregion

    // region The answer port's type

    @Test
    fun `the answer port carries the type the node asks for`() {
        assertEquals(ItemSchema.Primitive(String::class), answerSchema("TEXT"))
        assertEquals(ItemSchema.Primitive(Int::class), answerSchema("WHOLE_NUMBER"))
        assertEquals(ItemSchema.Primitive(Boolean::class), answerSchema("YES_OR_NO"))
    }

    @Test
    fun `an unconfigured node answers with text`() {
        assertEquals(ItemSchema.Primitive(String::class), answerSchema(null))
    }

    @Test
    fun `a narrower consumer of the same family pins the exact primitive`() {
        // The courtesy `transform.convert` already does: "whole number" is one
        // choice in the form, but a Long port still has to type-check exactly.
        // `action.if` set to compare Longs exposes exactly such a port.
        val dialog = node(DIALOG_INPUT_TYPE_ID, mapOf(DIALOG_INPUT_TYPE_KEY to "WHOLE_NUMBER"))
        val comparison = WorkflowNode(
            NodeId("i"), IF_TYPE_ID, "If", 0f, 100f,
            config = mapOf(IF_TYPE_CONFIG_KEY to ComparisonType.LONG.name),
        )
        val workflow = Workflow(
            nodes = listOf(dialog, comparison),
            dataConnections = listOf(DataConnection("c", dialog.id, DIALOG_VALUE_OUT, comparison.id, IF_VALUE_IN)),
        )
        val port = effectivePorts(NodeTypeRegistry.byId(DIALOG_INPUT_TYPE_ID)!!, workflow, dialog)
            .single { it.name == DIALOG_VALUE_OUT && it.direction == Direction.OUT }
        assertEquals(ItemSchema.Primitive(Long::class), port.schema)
    }

    // endregion

    private fun answerSchema(type: String?): ItemSchema? {
        val config = type?.let { mapOf(DIALOG_INPUT_TYPE_KEY to it) }.orEmpty()
        val placed = node(DIALOG_INPUT_TYPE_ID, config)
        return effectivePorts(NodeTypeRegistry.byId(DIALOG_INPUT_TYPE_ID)!!, Workflow(nodes = listOf(placed)), placed)
            .single { it.name == DIALOG_VALUE_OUT && it.direction == Direction.OUT }
            .schema
    }

    private fun portNames(typeId: NodeTypeId, timeout: String?) =
        node(typeId, timeout?.let { mapOf(DIALOG_TIMEOUT_KEY to it) }.orEmpty()).let { placed ->
            effectivePorts(NodeTypeRegistry.byId(typeId)!!, Workflow(nodes = listOf(placed)), placed).map { it.name }
        }

    private fun node(typeId: NodeTypeId, config: Map<ConfigKey, String>) =
        WorkflowNode(NodeId("d"), typeId, "Ask", 0f, 0f, config = config)
}
