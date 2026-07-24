package com.example.ottomatic.domain.registry

import com.example.ottomatic.core.model.NodeId
import com.example.ottomatic.core.model.NodeTypeId
import com.example.ottomatic.domain.model.Direction
import com.example.ottomatic.domain.model.PortKind
import com.example.ottomatic.domain.model.WorkflowNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract checks that hold for *every* registered node, so a new node cannot be
 * added with an incoherent declaration. These pass by construction now that the
 * config form, the data ports and the decoder are all derived from one config
 * class — the point of the test is to keep it that way.
 */
class NodeDeclarationContractTest {

    private val actions = ActionRegistry.all()
    private val triggers = TriggerRegistry.all()

    @Test
    fun `every node declares a config class that can be decoded unconfigured`() {
        for (action in actions) {
            val node = placed(action.typeId)
            // Throws if a property lacks a default or cannot be parsed.
            action.definition.schema.decode(node)
        }
        for (trigger in triggers) {
            trigger.definition.schema.decode(placed(trigger.typeId))
        }
    }

    @Test
    fun `every derived config field has a key a label and a usable default`() {
        for (schema in allConfigSchemas()) {
            for (field in schema.fields) {
                assertTrue("${schema.typeId}: blank config key", field.key.value.isNotBlank())
                assertTrue("${schema.typeId}.${field.key}: blank label", field.label.isNotBlank())
                val options = (field.type as? ConfigFieldType.ENUM)?.options ?: continue
                assertTrue("${schema.typeId}.${field.key}: enum with no options", options.isNotEmpty())
                assertTrue(
                    "${schema.typeId}.${field.key}: default '${field.defaultValue}' is not one of $options",
                    options.any { it.value == field.defaultValue },
                )
                assertTrue(
                    "${schema.typeId}.${field.key}: option labels must be non-blank",
                    options.all { it.label.isNotBlank() },
                )
            }
        }
    }

    @Test
    fun `config keys are unique within a node`() {
        for (schema in allConfigSchemas()) {
            val keys = schema.fields.map { it.key }
            assertEquals("${schema.typeId}: duplicate config keys in $keys", keys.distinct().size, keys.size)
        }
    }

    @Test
    fun `every wired config property is exposed as exactly one data input port`() {
        for (action in actions) {
            val definition = action.definition
            val declaredKeys = definition.schema.fields.map { it.key.value }.toSet()
            val wiredPorts = definition.schema.wiredPorts.map { it.name.value }
            assertEquals(
                "${action.typeId}: duplicate wired ports in $wiredPorts",
                wiredPorts.distinct().size,
                wiredPorts.size,
            )
            assertTrue(
                "${action.typeId}: wired ports $wiredPorts must all be config keys $declaredKeys",
                declaredKeys.containsAll(wiredPorts),
            )
        }
    }

    @Test
    fun `port names are unique per direction on every node`() {
        for (definition in NodeTypeRegistry.all) {
            for (direction in Direction.entries) {
                val names = definition.ports.filter { it.direction == direction }.map { it.name }
                assertEquals(
                    "${definition.typeId}: duplicate $direction port names in $names",
                    names.distinct().size,
                    names.size,
                )
            }
        }
    }

    @Test
    fun `every data port carries a schema and every execution port carries none`() {
        for (definition in NodeTypeRegistry.all) {
            for (port in definition.ports) {
                when (port.kind) {
                    PortKind.DATA -> assertTrue(
                        "${definition.typeId}.${port.name}: DATA port without a schema",
                        port.schema != null,
                    )
                    PortKind.EXECUTION -> assertTrue(
                        "${definition.typeId}.${port.name}: EXECUTION port must not carry a schema",
                        port.schema == null,
                    )
                }
            }
        }
    }

    @Test
    fun `a declared data output port is the one an action encodes onto`() {
        for (action in actions) {
            val output = action.definition.output ?: continue
            val declared = action.definition.nodeType.ports
                .filter { it.kind == PortKind.DATA && it.direction == Direction.OUT }
                .map { it.name }
            assertEquals("${action.typeId}: output port mismatch", listOf(output.name), declared)
        }
    }

    @Test
    fun `triggers expose one execution output and at most one data output`() {
        for (trigger in triggers) {
            val ports = trigger.definition.nodeType.ports
            val exec = ports.filter { it.kind == PortKind.EXECUTION }
            assertEquals("${trigger.typeId}: expected a single exec port", 1, exec.size)
            assertEquals(
                "${trigger.typeId}: trigger exec port must be an output",
                Direction.OUT,
                exec.single().direction,
            )
            assertTrue(
                "${trigger.typeId}: triggers must not expose data inputs",
                ports.none { it.kind == PortKind.DATA && it.direction == Direction.IN },
            )
            assertTrue(
                "${trigger.typeId}: at most one data output",
                ports.count { it.kind == PortKind.DATA && it.direction == Direction.OUT } <= 1,
            )
        }
    }

    private fun allConfigSchemas(): List<NodeConfigSchema> =
        actions.mapNotNull { it.definition.configSchema } + triggers.mapNotNull { it.definition.configSchema }

    private fun placed(typeId: NodeTypeId) = WorkflowNode(
        id = NodeId("n1"), typeId = typeId, name = typeId.value, x = 0f, y = 0f,
    )
}
