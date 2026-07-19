@file:Suppress("TooManyFunctions")

package com.example.ottomatic.domain.registry

import com.example.ottomatic.domain.model.Direction
import com.example.ottomatic.domain.model.NodeKind
import com.example.ottomatic.domain.model.NodeTypeDefinition
import com.example.ottomatic.domain.model.Port
import com.example.ottomatic.domain.model.PortKind
import com.example.ottomatic.domain.model.Workflow
import com.example.ottomatic.domain.model.WorkflowNode
import com.example.ottomatic.domain.model.schema.ItemSchema

/**
 * Resolves the *effective* port set and config schema of a placed [WorkflowNode].
 *
 * Most node types have a static port list ([NodeTypeDefinition.ports]) and a
 * static config schema ([ConfigSchemaRegistry]). Three independent mechanisms
 * extend or rewrite those static sets on a *placed* node:
 *
 *  1. **Exposed config inputs** — any ACTION node whose
 *     [WorkflowNode.exposedInputs] is non-empty gains one typed DATA input
 *     port per exposed config field (see [effectiveConfigSchema]). This is the
 *     primary way upstream data feeds into a node's configurable fields: the
 *     user toggles "Expose as data input" on a field in the configure sheet,
 *     wires an edge into the new port, and the incoming item overrides the
 *     static config value for that field at runtime.
 *
 *  2. **Dynamic struct ports** — `action.break` ([hasDynamicPorts] = true)
 *     derives one DATA output port per field of the struct connected to its
 *     `struct` input, by following the incoming edge back to its source port
 *     and reading that source's [ItemSchema]. Recursion through other dynamic
 *     nodes is safe because the graph validator guarantees data-edge
 *     acyclicity.
 *
 *  3. **Dynamic config schema** — `action.condition` rewrites its
 *     `field` / `operator` / `value` config fields from the schema of the
 *     data item connected to its [CONDITION_SOURCE_IN] port (see
 *     [effectiveConfigSchema]): the field picker becomes a typed dropdown of
 *     the struct's fields, the operator list narrows to those valid for the
 *     selected field's primitive type, and the compare-against literal is
 *     typed to match.
 */

/** EXECUTION input port. */
private fun execIn(name: String = "in"): Port =
    Port(name = name, kind = PortKind.EXECUTION, direction = Direction.IN)

/** EXECUTION output port. */
private fun execOut(name: String = "out"): Port =
    Port(name = name, kind = PortKind.EXECUTION, direction = Direction.OUT)

/** A DATA port with an explicit [ItemSchema]. */
private fun dataPort(name: String, direction: Direction, schema: ItemSchema): Port =
    Port(name = name, kind = PortKind.DATA, direction = direction, schema = schema)

/** The struct input port name on `action.break`. */
const val BREAK_STRUCT_IN = "struct"

/** typeId of the adaptive break-struct node. */
const val BREAK_TYPE_ID = "action.break"

/** typeId of the adaptive condition node. */
const val CONDITION_TYPE_ID = "action.condition"

/** The data input port name on `action.condition` carrying the value to compare. */
const val CONDITION_SOURCE_IN = "source"

/**
 * The effective ports for the placed [node] in [workflow]: the node type's
 * static [ports] (or the dynamic struct-derived ports for `action.break`),
 * plus one DATA input port per exposed config field on [node].
 */
fun effectivePorts(
    definition: NodeTypeDefinition,
    workflow: Workflow,
    node: WorkflowNode,
): List<Port> {
    val base = if (definition.hasDynamicPorts && definition.typeId == BREAK_TYPE_ID) {
        breakEffectivePorts(workflow, node)
    } else {
        definition.ports
    }
    return base + exposedFieldInputPorts(definition, workflow, node)
}

/** Effective input ports (convenience filter over [effectivePorts]). */
fun effectiveInputPorts(
    definition: NodeTypeDefinition,
    workflow: Workflow,
    node: WorkflowNode,
): List<Port> = effectivePorts(definition, workflow, node).filter { it.direction == Direction.IN }

/** Effective output ports (convenience filter over [effectivePorts]). */
fun effectiveOutputPorts(
    definition: NodeTypeDefinition,
    workflow: Workflow,
    node: WorkflowNode,
): List<Port> = effectivePorts(definition, workflow, node).filter { it.direction == Direction.OUT }

/**
 * Looks up a port by [name] on the placed [node], honouring dynamic and exposed
 * ports. Use this instead of [NodeTypeDefinition.port] for any placed node.
 *
 * [direction] is an optional filter. Supply it when the caller knows which
 * side of the node the port lives on: a node may expose a config field whose
 * key collides with a static output port name (e.g. `action.wifi` has a
 * `state` DATA output and a `state` config field that can be exposed as a
 * DATA input), so a name-only lookup is ambiguous on such nodes.
 */
fun effectivePort(
    definition: NodeTypeDefinition,
    workflow: Workflow,
    node: WorkflowNode,
    name: String,
    direction: Direction? = null,
): Port? = effectivePorts(definition, workflow, node).firstOrNull {
    it.name == name && (direction == null || it.direction == direction)
}

/**
 * The effective [NodeConfigSchema] for the placed [node]: the static schema
 * for ordinary nodes, and a schema-derived schema for `action.condition`.
 *
 * `action.condition` always exposes a `type` config field. When `type` is
 * `"auto"` (the default), the comparison type is inferred from whatever data
 * edge is wired into [CONDITION_SOURCE_IN]: a struct exposes a `field`
 * dropdown, a primitive is used directly. When `type` is a specific primitive
 * (`"int"`, `"string"`, ...), the `source` input port is locked to that
 * schema and no field picker is shown — this lets the user configure the
 * comparison without first connecting a data source.
 *
 *  - `field` (auto + struct only): ENUM of the connected struct's field names;
 *  - `operator`: narrowed to the operators valid for the selected/inferred
 *    primitive type (numeric, string, boolean);
 *  - `value` (compare-against): typed to match (INT / DOUBLE / BOOL / STR).
 *
 * Returns the static schema for any non-`action.condition` node, or null when
 * the node has no config schema.
 */
fun effectiveConfigSchema(
    definition: NodeTypeDefinition,
    workflow: Workflow,
    node: WorkflowNode,
): NodeConfigSchema? {
    if (definition.typeId == CONDITION_TYPE_ID) {
        return conditionConfigSchema(workflow, node)
    }
    return ConfigSchemaRegistry.byId(definition.typeId)
}

/** Config key for the condition's type chooser. */
const val CONDITION_TYPE_CONFIG_KEY = "type"

/** The "auto" option value for [CONDITION_TYPE_CONFIG_KEY]. */
const val CONDITION_TYPE_AUTO = "auto"

/** All options for the condition's type chooser. */
val CONDITION_TYPE_OPTIONS: List<String> =
    listOf(CONDITION_TYPE_AUTO, "int", "long", "double", "float", "boolean", "string")

/**
 * Builds the dynamic [NodeConfigSchema] for an `action.condition` node. Always
 * returns a schema (at minimum the `type` chooser), so the configure form can
 * be opened before any data edge is wired.
 *
 * Both `source` (the value to inspect) and `value` (the literal to compare
 * against) are [exposable][ConfigField.exposable]: by default they are form
 * literals, and toggling "Expose as data input" on either creates a typed
 * DATA IN port that overrides the literal at runtime. `type`, `operator`
 * and `field` are structural pickers and are never exposable.
 */
private fun conditionConfigSchema(workflow: Workflow, node: WorkflowNode): NodeConfigSchema {
    val typeConfig = node.config[CONDITION_TYPE_CONFIG_KEY]?.ifEmpty { CONDITION_TYPE_AUTO }
        ?: CONDITION_TYPE_AUTO

    val fields = mutableListOf(
        ConfigField(
            key = CONDITION_TYPE_CONFIG_KEY,
            label = "Type",
            type = ConfigFieldType.ENUM(options = CONDITION_TYPE_OPTIONS),
            defaultValue = CONDITION_TYPE_AUTO,
            exposable = false,
        ),
    )

    val comparisonSchema: ItemSchema? = if (typeConfig == CONDITION_TYPE_AUTO) {
        val connected = resolveInputSchema(workflow, node, CONDITION_SOURCE_IN)
        if (connected is ItemSchema.Object && connected.fields.isNotEmpty()) {
            val fieldOptions = connected.fields.keys.toList()
            val selected = node.config["field"]?.ifEmpty { fieldOptions.first() }
                ?: fieldOptions.first()
            fields += ConfigField(
                key = "field",
                label = "Field",
                type = ConfigFieldType.ENUM(options = fieldOptions),
                defaultValue = fieldOptions.first(),
                exposable = false,
            )
            connected.fields[selected]
        } else {
            connected
        }
    } else {
        primitiveSchemaFor(typeConfig)
    }

    // Always expose operator + source + value, even in auto mode with no
    // connection yet (defaulting to a string comparison) so the form is fully
    // configurable before any data edge is wired. `source` and `value` are
    // exposable; `operator` is not.
    val effectiveSchema = comparisonSchema ?: ItemSchema.Primitive(String::class)
    val operators = operatorOptionsFor(effectiveSchema)
    val literalType = valueConfigTypeFor(effectiveSchema)
    fields += ConfigField(
        key = "operator",
        label = "Operator",
        type = ConfigFieldType.ENUM(options = operators),
        defaultValue = operators.first(),
        exposable = false,
    )
    fields += ConfigField(
        key = CONDITION_SOURCE_IN,
        label = "Source",
        type = literalType,
    )
    fields += ConfigField(
        key = "value",
        label = "Compare against",
        type = literalType,
    )

    return NodeConfigSchema(typeId = CONDITION_TYPE_ID, fields = fields)
}

/**
 * The schema of the `source` DATA input on `action.condition` (used when the
 * `source` config field is exposed as a data port):
 *  - manual type → the chosen [ItemSchema.Primitive];
 *  - auto + connected → the connected source's schema (struct/primitive);
 *  - auto + unconnected → [ItemSchema.Wildcard] (accepts anything).
 */
private fun conditionSourceSchema(workflow: Workflow, node: WorkflowNode): ItemSchema {
    val typeConfig = node.config[CONDITION_TYPE_CONFIG_KEY]?.ifEmpty { CONDITION_TYPE_AUTO }
        ?: CONDITION_TYPE_AUTO
    if (typeConfig != CONDITION_TYPE_AUTO) {
        return primitiveSchemaFor(typeConfig) ?: ItemSchema.Wildcard
    }
    return resolveInputSchema(workflow, node, CONDITION_SOURCE_IN) ?: ItemSchema.Wildcard
}

/** Maps a type-chooser option string to its [ItemSchema.Primitive]. */
private fun primitiveSchemaFor(typeName: String): ItemSchema.Primitive? = when (typeName) {
    "int" -> ItemSchema.Primitive(Int::class)
    "long" -> ItemSchema.Primitive(Long::class)
    "double" -> ItemSchema.Primitive(Double::class)
    "float" -> ItemSchema.Primitive(Float::class)
    "boolean" -> ItemSchema.Primitive(Boolean::class)
    "string" -> ItemSchema.Primitive(String::class)
    else -> null
}

/** Narrowed operator list for a field of the given [schema]. */
private fun operatorOptionsFor(schema: ItemSchema?): List<String> =
    if (schema is ItemSchema.Primitive) {
        when (schema.kClass) {
            Int::class, Long::class, Double::class, Float::class ->
                listOf("equals", "notEquals", "greaterThan", "lessThan", "greaterThanOrEqual", "lessThanOrEqual")
            String::class ->
                listOf("equals", "notEquals", "contains", "matchesRegex")
            Boolean::class ->
                listOf("equals", "notEquals")
            else -> listOf("equals", "notEquals")
        }
    } else {
        listOf("equals", "notEquals")
    }

/** Typed `value` config field for a compare-against literal of the given [schema]. */
private fun valueConfigTypeFor(schema: ItemSchema?): ConfigFieldType<*> =
    if (schema is ItemSchema.Primitive) {
        when (schema.kClass) {
            Int::class -> ConfigFieldType.INT
            Long::class, Double::class, Float::class -> ConfigFieldType.DOUBLE
            Boolean::class -> ConfigFieldType.BOOL
            String::class -> ConfigFieldType.STR
            else -> ConfigFieldType.STR
        }
    } else {
        ConfigFieldType.STR
    }

/**
 * Ports for an `action.break` node: base (exec in/out + struct IN wildcard)
 * plus one DATA OUT per field of the struct connected to [BREAK_STRUCT_IN].
 */
private fun breakEffectivePorts(workflow: Workflow, node: WorkflowNode): List<Port> {
    val base = listOf(
        execIn(),
        execOut(),
        dataPort(BREAK_STRUCT_IN, Direction.IN, ItemSchema.Wildcard),
    )
    val schema = resolveInputSchema(workflow, node, BREAK_STRUCT_IN) as? ItemSchema.Object
        ?: return base
    return base + schema.fields.map { (name, fieldSchema) ->
        dataPort(name, Direction.OUT, fieldSchema)
    }
}

/**
 * The typed DATA input ports added to [node] for each config field the user
 * has toggled as exposed ([WorkflowNode.exposedInputs]). Each port is named
 * by the field key and typed by [ConfigField.portSchema]. Returns empty for
 * non-ACTION nodes (triggers are sources, not consumers) or nodes with no
 * config schema / no exposed fields.
 *
 * `action.condition`'s `source` field is special-cased: its port schema is
 * dynamic ([conditionSourceSchema], derived from the `type` config and any
 * connected edge) rather than the field's literal [ConfigFieldType].
 */
private fun exposedFieldInputPorts(definition: NodeTypeDefinition, workflow: Workflow, node: WorkflowNode): List<Port> {
    val schema = if (definition.kind == NodeKind.ACTION && node.exposedInputs.isNotEmpty()) {
        effectiveConfigSchema(definition, workflow, node)
    } else {
        null
    } ?: return emptyList()
    return schema.fields
        .filter { it.key in node.exposedInputs }
        .map { field ->
            val portSchema = if (definition.typeId == CONDITION_TYPE_ID && field.key == CONDITION_SOURCE_IN) {
                conditionSourceSchema(workflow, node)
            } else {
                field.portSchema()
            }
            dataPort(field.key, Direction.IN, portSchema)
        }
}

/**
 * Resolves the [ItemSchema] of the item arriving on the DATA input port named
 * [inputPortName] of [node], by following the incoming DATA edge back to its
 * source port. Used by `action.break` (struct IN) and `action.condition`
 * (source IN) to derive their dynamic port / config schemas. Returns null
 * when no edge is wired or the source has no schema.
 */
@Suppress("ReturnCount") // Null-guards on the optional edge/node/def path are idiomatic here.
private fun resolveInputSchema(
    workflow: Workflow,
    node: WorkflowNode,
    inputPortName: String,
): ItemSchema? {
    val edge = workflow.incomingData(node.id, inputPortName).firstOrNull() ?: return null
    val sourceNode = workflow.node(edge.fromNodeId) ?: return null
    val sourceDef = NodeTypeRegistry.byId(sourceNode.typeId) ?: return null
    val sourcePorts = if (sourceDef.hasDynamicPorts) {
        effectivePorts(sourceDef, workflow, sourceNode)
    } else {
        sourceDef.ports
    }
    return sourcePorts.firstOrNull {
        it.name == edge.fromPort && it.kind == PortKind.DATA && it.direction == Direction.OUT
    }?.schema
}
