package com.example.ottomatic.domain.registry

import com.example.ottomatic.core.model.ConfigKey
import com.example.ottomatic.core.model.NodeId
import com.example.ottomatic.core.model.NodeTypeId
import com.example.ottomatic.domain.model.Direction
import com.example.ottomatic.domain.model.PortKind
import com.example.ottomatic.domain.model.WorkflowNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    private val conditions = ConditionRegistry.all()

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
        for (condition in conditions) {
            // Attached conditions decode from a bare config map, with no node
            // behind them — the defaults must carry the whole thing.
            condition.definition.schema.decode(emptyMap())
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
        val schemas = actions.map { it.typeId to it.definition.schema } +
            conditions.map { it.typeId to it.definition.schema }
        for ((typeId, schema) in schemas) {
            val declaredKeys = schema.fields.map { it.key.value }.toSet()
            val wiredPorts = schema.wiredPorts.map { it.name.value }
            assertEquals(
                "$typeId: duplicate wired ports in $wiredPorts",
                wiredPorts.distinct().size,
                wiredPorts.size,
            )
            assertTrue(
                "$typeId: wired ports $wiredPorts must all be config keys $declaredKeys",
                declaredKeys.containsAll(wiredPorts),
            )
        }
    }

    @Test
    fun `every visibility rule points at a real sibling field and real option values`() {
        for (schema in allConfigSchemas()) {
            val byKey = schema.fields.associateBy { it.key }
            val ruled = schema.fields.mapNotNull { field -> field.visibleWhen?.let { field to it } }
            for ((field, rule) in ruled) {
                // A rule naming a key that does not exist would hide nothing and
                // read as if it worked; a value that is not an option of the
                // controlling field would hide the property forever.
                val controlling = byKey[rule.key]
                assertTrue(
                    "${schema.typeId}.${field.key}: @VisibleWhen names unknown key '${rule.key.value}'",
                    controlling != null,
                )
                val values = allowedValues(controlling!!.type)
                assertTrue(
                    "${schema.typeId}.${field.key}: @VisibleWhen values ${rule.values} are not all in $values",
                    values == null || values.containsAll(rule.values),
                )
            }
        }
    }

    /**
     * The values a field of [type] can actually hold, or null for a type whose
     * value set is open (a number, free text, a picked identifier) and so
     * cannot be checked.
     *
     * A Boolean's set is checkable even though it has no declared options —
     * `@VisibleWhen("onDwell", "yes")` would otherwise hide the controlled
     * field forever, because a switch only ever stores "true" or "false".
     */
    private fun allowedValues(type: ConfigFieldType<*>): Set<String>? = when (type) {
        is ConfigFieldType.ENUM -> type.options.map { it.value }.toSet()
        ConfigFieldType.BOOL -> setOf("true", "false")
        else -> null
    }

    @Test
    fun `visibility rules never form a cycle`() {
        // Rules nest — a field is shown only when its controller is shown too —
        // so a cycle would be a set of fields that can never appear, and is worth
        // rejecting at declaration time rather than debugging in the editor.
        for (schema in allConfigSchemas()) {
            val byKey = schema.fields.associateBy { it.key }
            for (field in schema.fields) {
                val cycle = cycleFrom(field, byKey)
                assertNull("${schema.typeId}.${field.key}: @VisibleWhen chain cycles through $cycle", cycle)
            }
        }
    }

    /**
     * The first key revisited while walking [field]'s controller chain, or null
     * when the chain terminates. The sequence is lazy, so a cycle is caught on
     * its first repeat rather than walked forever.
     */
    private fun cycleFrom(field: ConfigField<*>, byKey: Map<ConfigKey, ConfigField<*>>): ConfigKey? {
        val seen = mutableSetOf(field.key)
        return generateSequence(field) { it.visibleWhen?.key?.let(byKey::get) }
            .drop(1)
            .firstOrNull { !seen.add(it.key) }
            ?.key
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

    @Test
    fun `every condition branches and produces no data`() {
        for (condition in conditions) {
            val ports = condition.definition.nodeType.ports
            val execOut = ports.filter { it.kind == PortKind.EXECUTION && it.direction == Direction.OUT }
            assertEquals(
                "${condition.typeId}: a condition must expose true/false exec outputs",
                listOf("true", "false"),
                execOut.map { it.name.value },
            )
            assertTrue(
                "${condition.typeId}: a condition answers a question, it must not produce data",
                ports.none { it.kind == PortKind.DATA && it.direction == Direction.OUT },
            )
        }
    }

    private fun allConfigSchemas(): List<NodeConfigSchema> =
        actions.mapNotNull { it.definition.configSchema } +
            triggers.mapNotNull { it.definition.configSchema } +
            conditions.mapNotNull { it.definition.configSchema }

    private fun placed(typeId: NodeTypeId) = WorkflowNode(
        id = NodeId("n1"), typeId = typeId, name = typeId.value, x = 0f, y = 0f,
    )
}
