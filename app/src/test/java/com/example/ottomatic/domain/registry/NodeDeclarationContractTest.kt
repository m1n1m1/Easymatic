package com.example.ottomatic.domain.registry

import com.example.ottomatic.core.model.ConfigKey
import com.example.ottomatic.core.model.NodeId
import com.example.ottomatic.core.model.NodeTypeId
import com.example.ottomatic.domain.model.DataConnection
import com.example.ottomatic.domain.model.Direction
import com.example.ottomatic.domain.model.PortKind
import com.example.ottomatic.domain.model.VariableDeclaration
import com.example.ottomatic.domain.model.VariableRef
import com.example.ottomatic.domain.model.Workflow
import com.example.ottomatic.domain.model.WorkflowNode
import com.example.ottomatic.domain.model.config.ValueType
import com.example.ottomatic.domain.model.schema.ItemSchema
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
    private val values = ValueRegistry.all()
    private val transforms = TransformRegistry.all()

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
        for (value in values) {
            // A value is read from a bare config map, with nothing wired into it —
            // the defaults must carry the whole thing.
            value.definition.schema.decode(emptyMap())
        }
        for (transform in transforms) {
            transform.definition.schema.decode(emptyMap())
        }
        // The comparison must decode from bare defaults too: a freshly placed
        // `action.if` has an empty config until the user touches its form.
        ActionRegistry.byId(IF_TYPE_ID)!!.definition.schema.decode(emptyMap())
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
            values.map { it.typeId to it.definition.schema } +
            transforms.map { it.typeId to it.definition.schema }
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

    /**
     * The purity contract for value nodes, and the reason they can be pulled.
     *
     * A value node is read *outside* the execution order — with no pulse, at a moment
     * decided by whoever consumes it. That is only sound while a read is cheap,
     * repeatable and cannot fail loudly, so the shape that guarantees it is enforced
     * here rather than left to convention: no exec ports (nothing to sequence) and no
     * data inputs (a leaf, so no recursive resolution). Anything expensive or failable
     * belongs in an action, where it has a place in the exec chain.
     *
     * A **permission is deliberately not on that list**, though it used to be. The
     * contract is about ports and effects, and a grant is neither: `value.wifi_network`
     * needs `ACCESS_FINE_LOCATION` to name a network and is otherwise exactly as cheap
     * and repeatable as `value.battery`. Forbidding the declaration did not make such a
     * read safe, it only made it *silent* — the node returned null forever and neither
     * the Problems panel nor the Permissions screen, both of which walk node
     * declarations, had anything to say about why. What a value still may not do is
     * fail loudly: a missing grant has to come back as null so the consumer falls back,
     * which is a property of the reader and not something a port count can check.
     */
    @Test
    fun `every value is a pure leaf with exactly one data output`() {
        for (value in values) {
            val ports = value.definition.nodeType.ports
            assertTrue(
                "${value.typeId}: a value is never pulsed, it must declare no execution ports",
                ports.none { it.kind == PortKind.EXECUTION },
            )
            assertTrue(
                "${value.typeId}: a value is a leaf, it must declare no data inputs (no @Wired properties)",
                ports.none { it.kind == PortKind.DATA && it.direction == Direction.IN },
            )
            assertEquals(
                "${value.typeId}: a value must expose exactly one data output",
                1,
                ports.count { it.kind == PortKind.DATA && it.direction == Direction.OUT },
            )
        }
    }

    /**
     * The purity contract for transforms — the other half of the pull side.
     *
     * A transform is read outside the execution order for the same reason a value
     * is, so it carries the same bar: no exec ports and no permissions. What it does
     * *not* share is leafness — a transform is a function, so it must have something
     * to be a function of, and exactly one answer to give. Without the "at least one
     * data input" half, a transform would be a value node with extra steps; without
     * the "exactly one output" half, the pull memo (which is keyed by node, not port)
     * would silently serve one port's item to another.
     */
    @Test
    fun `every transform is a pure function with inputs and exactly one data output`() {
        for (transform in transforms) {
            val ports = transform.definition.nodeType.ports
            assertTrue(
                "${transform.typeId}: a transform is never pulsed, it must declare no execution ports",
                ports.none { it.kind == PortKind.EXECUTION },
            )
            assertTrue(
                "${transform.typeId}: a transform is a function, it must declare at least one data input",
                ports.any { it.kind == PortKind.DATA && it.direction == Direction.IN },
            )
            assertEquals(
                "${transform.typeId}: a transform must expose exactly one data output",
                1,
                ports.count { it.kind == PortKind.DATA && it.direction == Direction.OUT },
            )
            assertTrue(
                "${transform.typeId}: a transform must not require a permission — a read cannot prompt",
                transform.definition.nodeType.permissionRequirements.isEmpty(),
            )
        }
    }

    /**
     * An adaptive transform declares a wildcard output and has it retyped by
     * [effectivePorts]. A typeId missing from that `when` would keep the wildcard
     * forever, so every edge out of it would be accepted and nothing downstream
     * would ever narrow — the failure is silent, hence the check.
     *
     * The retyping is *provoked* rather than assumed: a list transform reads its
     * answer from whatever is wired in, so unwired it is a wildcard quite
     * legitimately, and only feeding it a list of known type tells "resolved to
     * wildcard because nothing is connected" apart from "resolved to wildcard
     * because nobody added it to the `when`". The config-typed ones
     * (`transform.convert`, `transform.json_read`) ignore the edge and answer from
     * their own defaults, which is equally a pass.
     */
    @Test
    fun `every adaptive transform is retyped by effectivePorts`() {
        for (transform in transforms.filter { it.definition.hasDynamicPorts }) {
            val definition = transform.definition.nodeType
            val node = placed(transform.typeId)
            val resolved = effectivePorts(definition, fedWithAList(node), node)
                .single { it.kind == PortKind.DATA && it.direction == Direction.OUT }
            assertTrue(
                "${transform.typeId}: adaptive output was left as ${resolved.schema}; " +
                    "add it to effectivePorts",
                resolved.schema !is ItemSchema.Wildcard,
            )
        }
    }

    /**
     * The same check for the one adaptive *value*, which the transform test above
     * cannot cover because it filters the transform registry.
     *
     * `value.variable` declares a wildcard and takes its type from the declaration
     * it names, so the retyping is provoked by giving the workflow one — an
     * undeclared ref legitimately stays a wildcard, and only a declared one tells
     * "nothing chosen" apart from "nobody added it to the `when`".
     */
    @Test
    fun `every adaptive value is retyped by effectivePorts`() {
        val declaration = VariableDeclaration(id = "v1", name = "counter", type = ValueType.WHOLE_NUMBER)
        for (value in values.filter { it.definition.hasDynamicPorts }) {
            val node = placed(value.typeId).copy(config = mapOf(VARIABLE_REF_KEY to VariableRef.localSpec("v1")))
            val workflow = Workflow(id = "w", name = "w", nodes = listOf(node), variables = listOf(declaration))
            val resolved = effectivePorts(value.definition.nodeType, workflow, node)
                .single { it.kind == PortKind.DATA && it.direction == Direction.OUT }
            assertTrue(
                "${value.typeId}: adaptive output was left as ${resolved.schema}; add it to effectivePorts",
                resolved.schema !is ItemSchema.Wildcard,
            )
        }
    }

    /**
     * A one-edge graph feeding [node]'s first declared data input from a list of
     * text — `transform.split_text` being the shortest way to name one.
     */
    private fun fedWithAList(node: WorkflowNode): Workflow {
        val source = WorkflowNode(
            id = NodeId("source"),
            typeId = NodeTypeId("transform.split_text"),
            name = "source",
            x = 0f,
            y = 0f,
        )
        val into = NodeTypeRegistry.byId(node.typeId)!!.ports
            .first { it.kind == PortKind.DATA && it.direction == Direction.IN }
        return Workflow(
            id = "w",
            name = "w",
            nodes = listOf(source, node),
            dataConnections = listOf(
                DataConnection(
                    id = "e1",
                    fromNodeId = source.id,
                    fromPort = TRANSFORM_OUT,
                    toNodeId = node.id,
                    toPort = into.name,
                ),
            ),
        )
    }

    private fun allConfigSchemas(): List<NodeConfigSchema> =
        actions.mapNotNull { it.definition.configSchema } +
            triggers.mapNotNull { it.definition.configSchema } +
            values.mapNotNull { it.definition.configSchema } +
            transforms.mapNotNull { it.definition.configSchema }

    private fun placed(typeId: NodeTypeId) = WorkflowNode(
        id = NodeId("n1"), typeId = typeId, name = typeId.value, x = 0f, y = 0f,
    )
}
