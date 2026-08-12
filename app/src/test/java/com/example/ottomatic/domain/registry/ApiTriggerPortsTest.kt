package com.example.ottomatic.domain.registry

import com.example.ottomatic.core.model.ConfigKey
import com.example.ottomatic.core.model.NodeId
import com.example.ottomatic.domain.model.Direction
import com.example.ottomatic.domain.model.NodeKind
import com.example.ottomatic.domain.model.PortKind
import com.example.ottomatic.domain.model.PortSpec
import com.example.ottomatic.domain.model.Workflow
import com.example.ottomatic.domain.model.WorkflowNode
import com.example.ottomatic.domain.model.schema.ItemSchema
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `trigger.api`'s registration and its config-driven output ports.
 *
 * Both halves matter and they fail differently: a missing registry entry is a node
 * absent from the palette, where a missing `effectivePorts` branch is a node that is
 * present, looks right and has no data ports at all.
 */
class ApiTriggerPortsTest {

    private fun node(config: Map<ConfigKey, String>) = WorkflowNode(
        id = NodeId("n1"),
        typeId = API_TRIGGER_TYPE_ID,
        name = "Called by Another App",
        x = 0f,
        y = 0f,
        config = config,
    )

    private fun ports(config: Map<ConfigKey, String>) = node(config).let { n ->
        effectivePorts(NodeTypeRegistry.byId(API_TRIGGER_TYPE_ID)!!, Workflow(nodes = listOf(n)), n)
    }

    private fun dataOut(config: Map<ConfigKey, String>) =
        ports(config).filter { it.kind == PortKind.DATA && it.direction == Direction.OUT }

    @Test
    fun `it is registered as a trigger`() {
        val definition = NodeTypeRegistry.byId(API_TRIGGER_TYPE_ID)
        assertNotNull("trigger.api is not registered", definition)
        assertEquals(NodeKind.TRIGGER, definition!!.kind)
        assertTrue("an adaptive node must say so, or effectivePorts is never asked", definition.hasDynamicPorts)
    }

    @Test
    fun `it declares one exec output and no data ports of its own`() {
        val declared = NodeTypeRegistry.byId(API_TRIGGER_TYPE_ID)!!.ports
        assertEquals(1, declared.size)
        assertEquals(PortKind.EXECUTION, declared.single().kind)
        assertEquals(Direction.OUT, declared.single().direction)
    }

    @Test
    fun `a blank config declares no data ports`() {
        // Unlike `action.script`, whose outputs fall back to `result`: a trigger that
        // carries no data is completely ordinary.
        assertTrue(dataOut(emptyMap()).isEmpty())
        assertTrue(dataOut(mapOf(API_INPUTS_KEY to "")).isEmpty())
    }

    @Test
    fun `each configured row becomes a typed data output`() {
        val resolved = dataOut(mapOf(API_INPUTS_KEY to "city:TEXT\ncount:WHOLE_NUMBER"))
        assertEquals(listOf("city", "count"), resolved.map { it.name.value })
        assertEquals(ItemSchema.Primitive(String::class), resolved[0].schema)
        assertEquals(ItemSchema.Primitive(Int::class), resolved[1].schema)
    }

    @Test
    fun `an untyped row becomes a wildcard port`() {
        assertEquals(ItemSchema.Wildcard, dataOut(mapOf(API_INPUTS_KEY to "blob:ANY")).single().schema)
    }

    @Test
    fun `a list row becomes a list port`() {
        assertEquals(
            ItemSchema.ListSchema(ItemSchema.Primitive(String::class)),
            dataOut(mapOf(API_INPUTS_KEY to "tags:TEXT[]")).single().schema,
        )
    }

    /**
     * Silently rebinding the exec port to a caller's value would be a far worse
     * surprise than a missing handle — `scriptEffectivePorts`' rule, and the reason
     * this node shares it.
     */
    @Test
    fun `a row colliding with a declared port is dropped`() {
        val declared = NodeTypeRegistry.byId(API_TRIGGER_TYPE_ID)!!.ports.single().name.value
        val resolved = ports(mapOf(API_INPUTS_KEY to "$declared:TEXT\ncity:TEXT"))
        assertEquals(1, resolved.count { it.name.value == declared })
        assertEquals(PortKind.EXECUTION, resolved.single { it.name.value == declared }.kind)
    }

    @Test
    fun `the port count is bounded`() {
        val many = (1..(PortSpec.MAX_PORTS + 5)).joinToString("\n") { "p$it:TEXT" }
        assertEquals(PortSpec.MAX_PORTS, dataOut(mapOf(API_INPUTS_KEY to many)).size)
    }

    @Test
    fun `an unreadable row is dropped rather than throwing`() {
        // The editor rebuilds the card on every keystroke, so a half-typed line must
        // never be an exception.
        val resolved = dataOut(mapOf(API_INPUTS_KEY to "\n  \ncity:TEXT\n:::\n9bad:TEXT"))
        assertEquals(listOf("city"), resolved.map { it.name.value })
    }
}
