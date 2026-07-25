@file:Suppress("TooManyFunctions")

package com.example.ottomatic.domain.registry

import com.example.ottomatic.core.model.ConfigKey
import com.example.ottomatic.domain.model.AttachedCondition
import com.example.ottomatic.domain.model.Direction
import com.example.ottomatic.domain.model.ExecPorts
import com.example.ottomatic.domain.model.NodeTypeDefinition
import com.example.ottomatic.core.model.NodeTypeId
import com.example.ottomatic.domain.model.Port
import com.example.ottomatic.domain.model.PortKind
import com.example.ottomatic.core.model.PortName
import com.example.ottomatic.domain.model.Workflow
import com.example.ottomatic.domain.model.WorkflowNode
import com.example.ottomatic.domain.model.config.ComparisonOperator
import com.example.ottomatic.domain.model.config.ComparisonType
import com.example.ottomatic.domain.model.execIn
import com.example.ottomatic.domain.model.execOut
import com.example.ottomatic.domain.model.schema.ItemSchema

/**
 * Resolves the *effective* port set and config schema of a placed [WorkflowNode].
 *
 * Most node types have a static port list ([NodeTypeDefinition.ports]) and a
 * static config schema derived from their config class ([ConfigSchemaRegistry]).
 * Two independent mechanisms rewrite those static sets on a *placed* node:
 *
 *  1. **Dynamic struct ports** — `action.break` derives one DATA output port per
 *     field of the struct connected to its `struct` input, by following the
 *     incoming edge back to its source port and reading that source's
 *     [ItemSchema]. Recursion through other dynamic nodes is safe because the
 *     graph validator guarantees data-edge acyclicity.
 *
 *  2. **Dynamic condition ports + config** — `condition.compare` rewrites its
 *     `source`/`value` DATA input port schemas, and *narrows* the config form
 *     derived from `ConditionConfig` (see [effectiveConfigSchema]): the field
 *     picker becomes a typed dropdown of the struct's fields, the operator list
 *     shrinks to those valid for the selected field's type, and the
 *     compare-against literal is retyped to match.
 *
 * All other DATA input ports are derived from the `@Wired` properties of a
 * node's config class and are always present; the same property supplies the
 * static form value used when no edge is wired.
 */

/** A DATA port with an explicit [ItemSchema]. */
private fun dataPort(name: PortName, direction: Direction, schema: ItemSchema, label: String = name.value): Port =
    Port(name = name, kind = PortKind.DATA, direction = direction, schema = schema, label = label)

/** The struct input port on `action.break`. */
val BREAK_STRUCT_IN = PortName("struct")

/** typeId of the adaptive break-struct node. */
val BREAK_TYPE_ID = NodeTypeId("action.break")

/** typeId of the adaptive comparison condition. */
val CONDITION_TYPE_ID = NodeTypeId("condition.compare")

/** The data input port on `condition.compare` carrying the value to inspect. */
val CONDITION_SOURCE_IN = PortName("source")

/** The data input port on `condition.compare` carrying the value to compare against. */
val CONDITION_VALUE_IN = PortName("value")

/** The config key of `condition.compare`'s field picker. */
val CONDITION_FIELD_KEY = ConfigKey("field")

/** The config key of `condition.compare`'s operator picker. */
val CONDITION_OPERATOR_KEY = ConfigKey("operator")

/** The config key of `condition.compare`'s type chooser. */
val CONDITION_TYPE_CONFIG_KEY = ConfigKey("type")

/**
 * The effective ports for the placed [node] in [workflow]: the node type's
 * static ports, with dynamic rewrites for `action.break` (struct-derived
 * output ports) and `condition.compare` (dynamic `source`/`value` input schemas).
 */
fun effectivePorts(
    definition: NodeTypeDefinition,
    workflow: Workflow,
    node: WorkflowNode,
): List<Port> = when (definition.typeId) {
    BREAK_TYPE_ID -> breakEffectivePorts(workflow, node)
    CONDITION_TYPE_ID -> conditionEffectivePorts(workflow, node)
    else -> definition.ports
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
 * Looks up a port by [name] on the placed [node], honouring dynamic ports.
 * Use this instead of [NodeTypeDefinition.port] for any placed node.
 *
 * [direction] is an optional filter. Supply it when the caller knows which
 * side of the node the port lives on.
 */
fun effectivePort(
    definition: NodeTypeDefinition,
    workflow: Workflow,
    node: WorkflowNode,
    name: PortName,
    direction: Direction? = null,
): Port? = effectivePorts(definition, workflow, node).firstOrNull {
    it.name == name && (direction == null || it.direction == direction)
}

/**
 * The effective [NodeConfigSchema] for the placed [node]: the schema derived
 * from the node's config class, graph-narrowed for `condition.compare`, then
 * filtered down to the fields whose `@VisibleWhen` condition the node's own
 * config currently satisfies.
 *
 * Returns null when the node has no configurable fields left.
 */
fun effectiveConfigSchema(
    definition: NodeTypeDefinition,
    workflow: Workflow,
    node: WorkflowNode,
): NodeConfigSchema? {
    val declared = ConfigSchemaRegistry.byId(definition.typeId) ?: return null
    val narrowed = if (definition.typeId == CONDITION_TYPE_ID) {
        conditionConfigSchema(declared, workflow, node)
    } else {
        declared
    }
    return narrowed.visibleFor(node.config).takeIf { it.fields.isNotEmpty() }
}

/**
 * The effective [NodeConfigSchema] for an [AttachedCondition] on [host]: the same
 * derivation as [effectiveConfigSchema], but for a condition that has no node and
 * therefore no incoming edges of its own.
 *
 * The narrowing differs in exactly one place. A *placed* `condition.compare`
 * inspects whatever is wired into its own `source` port; an *attached* one has
 * nothing wired to it, so `source` instead becomes a picker over the host node's
 * own DATA input ports — the items the gate will actually be handed at runtime
 * (see [com.example.ottomatic.engine.conditionsPass]). That is what makes an
 * attached comparison useful without projecting new ports onto the host.
 *
 * Returns null when the condition has no configurable fields.
 */
fun effectiveConditionSchema(
    workflow: Workflow,
    host: WorkflowNode,
    attached: AttachedCondition,
): NodeConfigSchema? {
    val declared = ConfigSchemaRegistry.byId(attached.typeId) ?: return null
    val narrowed = if (attached.typeId == CONDITION_TYPE_ID) {
        attachedCompareSchema(declared, workflow, host, attached.config)
    } else {
        declared
    }
    return narrowed.visibleFor(attached.config).takeIf { it.fields.isNotEmpty() }
}

/**
 * Narrows an attached `condition.compare` form against its [host]: `source`
 * becomes an ENUM of the host's DATA input port names, and the operator list and
 * compare-against literal are typed from the port the user picked.
 */
private fun attachedCompareSchema(
    declared: NodeConfigSchema,
    workflow: Workflow,
    host: WorkflowNode,
    config: Map<ConfigKey, String>,
): NodeConfigSchema {
    val definition = NodeTypeRegistry.byId(host.typeId)
    val inputs = definition
        ?.let { effectiveInputPorts(it, workflow, host) }
        ?.filter { it.kind == PortKind.DATA }
        .orEmpty()
    val sourceKey = ConfigKey(CONDITION_SOURCE_IN.value)
    val selected = inputs.firstOrNull { it.name.value == config[sourceKey] } ?: inputs.firstOrNull()
    val structFields = (selected?.schema as? ItemSchema.Object)?.fields?.keys?.toList()?.takeIf { it.isNotEmpty() }
    val inspected = attachedInspectedSchema(selected?.schema, structFields, config)
    val operators = ComparisonOperator.forSchema(inspected)

    val fields = declared.fields.mapNotNull { field ->
        when (field.key) {
            // Pinning a primitive type is meaningless without a port to retype.
            CONDITION_TYPE_CONFIG_KEY -> null
            sourceKey -> inputs.takeIf { it.isNotEmpty() }?.let { ports ->
                ConfigField(
                    key = field.key,
                    label = "Input",
                    type = ConfigFieldType.ENUM(ports.map { ConfigOption(it.name.value, it.label) }),
                    defaultValue = ports.first().name.value,
                )
            }
            CONDITION_FIELD_KEY -> structFields?.let { field.asChoice(it, default = it.first()) }
            CONDITION_OPERATOR_KEY -> field.asChoice(operators.map { it.name }, default = operators.first().name)
            ConfigKey(CONDITION_VALUE_IN.value) ->
                ConfigField(field.key, field.label, literalTypeFor(inspected), field.defaultValue)
            else -> field
        }
    }
    return NodeConfigSchema(typeId = CONDITION_TYPE_ID, fields = fields)
}

/** The schema of the value an attached comparison actually inspects. */
private fun attachedInspectedSchema(
    portSchema: ItemSchema?,
    structFields: List<String>?,
    config: Map<ConfigKey, String>,
): ItemSchema {
    if (structFields != null && portSchema is ItemSchema.Object) {
        val selected = config[CONDITION_FIELD_KEY]?.takeIf { it in structFields } ?: structFields.first()
        return portSchema.fields[selected] ?: ItemSchema.Primitive(String::class)
    }
    return portSchema ?: ItemSchema.Primitive(String::class)
}

/**
 * Drops the fields whose [ConfigField.visibleWhen] rule [config] does not
 * satisfy. The controlling value is read from [config], falling back to the
 * controlling *field's* declared default — so a node the user has never touched
 * shows the fields belonging to its default mode rather than none of them.
 *
 * Takes the config map rather than a [WorkflowNode] so the same rule evaluation
 * serves both a placed node's config and an [AttachedCondition]'s.
 *
 * Rules nest: a field is visible only when its own rule holds *and* its
 * controlling field is itself visible. Without that, switching an outer mode
 * hides the control but leaves the fields it gates on screen, stranded behind a
 * switch the user can no longer see.
 *
 * A rule naming an unknown key hides nothing; the declaration contract test
 * rejects that case, and rule cycles, at build time rather than leaving a field
 * silently unreachable at runtime.
 */
private fun NodeConfigSchema.visibleFor(config: Map<ConfigKey, String>): NodeConfigSchema {
    if (fields.none { it.visibleWhen != null }) return this
    val byKey = fields.associateBy { it.key }
    return copy(fields = fields.filter { it.isVisibleFor(config, byKey) })
}

/**
 * Whether this field's whole rule chain holds for [config]: its own rule, its
 * controller's, and so on up to a field that declares none.
 *
 * The walk tracks the keys already visited, so a cyclic declaration terminates
 * after evaluating each field in the cycle once rather than looping. The
 * declaration contract test rejects such cycles outright; this only keeps the
 * editor from hanging on one.
 */
private fun ConfigField<*>.isVisibleFor(
    config: Map<ConfigKey, String>,
    byKey: Map<ConfigKey, ConfigField<*>>,
): Boolean {
    val seen = mutableSetOf(key)
    val chain = generateSequence(this) { field ->
        field.visibleWhen?.key?.let { byKey[it] }?.takeIf { seen.add(it.key) }
    }
    return chain.all { field ->
        val rule = field.visibleWhen ?: return@all true
        // A rule naming an unknown key hides nothing.
        val controlling = byKey[rule.key] ?: return@all true
        (config[rule.key] ?: controlling.defaultValue) in rule.values
    }
}

/**
 * Narrows `condition.compare`'s [declared] form (derived from `ConditionConfig`)
 * against the graph:
 *  - the field picker is dropped unless the inspected value is a struct, and
 *    otherwise becomes an ENUM of that struct's field names;
 *  - the operator list shrinks to those valid for the inspected type;
 *  - `source` and `value` are retyped to the inspected primitive.
 */
private fun conditionConfigSchema(
    declared: NodeConfigSchema,
    workflow: Workflow,
    node: WorkflowNode,
): NodeConfigSchema {
    val structFields = conditionStructFields(workflow, node)
    val inspected = inspectedSchema(workflow, node, structFields)
    val operators = ComparisonOperator.forSchema(inspected)
    val literal = literalTypeFor(inspected)
    val sourceKey = ConfigKey(CONDITION_SOURCE_IN.value)
    val valueKey = ConfigKey(CONDITION_VALUE_IN.value)

    val fields = declared.fields.mapNotNull { field ->
        when (field.key) {
            CONDITION_FIELD_KEY -> structFields?.let { field.asChoice(it, default = it.first()) }
            CONDITION_OPERATOR_KEY -> field.asChoice(operators.map { it.name }, default = operators.first().name)
            sourceKey, valueKey -> ConfigField(field.key, field.label, literal, field.defaultValue)
            else -> field
        }
    }
    return NodeConfigSchema(typeId = CONDITION_TYPE_ID, fields = fields)
}

/** Replaces a derived field's type with an ENUM over [options]. */
private fun ConfigField<*>.asChoice(options: List<String>, default: String): ConfigField<String> = ConfigField(
    key = key,
    label = label,
    type = ConfigFieldType.ENUM(options.map { ConfigOption(it, prettifyOption(it)) }),
    defaultValue = default,
)

/** `GREATER_THAN` → "Greater than"; struct field names are left as they are. */
private fun prettifyOption(value: String): String =
    if (value.any { it.isLowerCase() }) {
        value
    } else {
        value.lowercase().replace('_', ' ').replaceFirstChar { it.uppercaseChar() }
    }

/**
 * The field names of the struct wired into `source`, or null when the inspected
 * value is not a struct (or the type is pinned to a primitive).
 */
private fun conditionStructFields(workflow: Workflow, node: WorkflowNode): List<String>? {
    if (conditionType(node) != ComparisonType.AUTO) return null
    val connected = resolveInputSchema(workflow, node, CONDITION_SOURCE_IN)
    return (connected as? ItemSchema.Object)?.fields?.keys?.toList()?.takeIf { it.isNotEmpty() }
}

/**
 * The schema of the value the condition actually compares: the selected struct
 * field, the connected primitive, the pinned primitive, or String as a fallback
 * so the form is usable before anything is wired.
 */
private fun inspectedSchema(workflow: Workflow, node: WorkflowNode, structFields: List<String>?): ItemSchema {
    val type = conditionType(node)
    if (type != ComparisonType.AUTO) return type.schema ?: ItemSchema.Primitive(String::class)
    val connected = resolveInputSchema(workflow, node, CONDITION_SOURCE_IN)
    if (structFields != null && connected is ItemSchema.Object) {
        val selected = node.config[CONDITION_FIELD_KEY]?.takeIf { it in structFields } ?: structFields.first()
        return connected.fields[selected] ?: ItemSchema.Primitive(String::class)
    }
    return connected ?: ItemSchema.Primitive(String::class)
}

/** The condition's configured [ComparisonType], defaulting to [ComparisonType.AUTO]. */
private fun conditionType(node: WorkflowNode): ComparisonType {
    val raw = node.config[CONDITION_TYPE_CONFIG_KEY]?.takeIf { it.isNotBlank() } ?: return ComparisonType.AUTO
    return runCatching { ComparisonType.valueOf(raw) }.getOrDefault(ComparisonType.AUTO)
}

/** The form type of a compare-against literal of the given [schema]. */
private fun literalTypeFor(schema: ItemSchema?): ConfigFieldType<*> =
    if (schema is ItemSchema.Primitive) {
        when (schema.kClass) {
            Int::class -> ConfigFieldType.INT
            Long::class, Double::class, Float::class -> ConfigFieldType.DOUBLE
            Boolean::class -> ConfigFieldType.BOOL
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
        dataPort(BREAK_STRUCT_IN, Direction.IN, ItemSchema.Wildcard, label = "Struct"),
    )
    val schema = resolveInputSchema(workflow, node, BREAK_STRUCT_IN) as? ItemSchema.Object ?: return base
    return base + schema.fields.map { (name, fieldSchema) -> dataPort(PortName(name), Direction.OUT, fieldSchema) }
}

/**
 * Effective ports for `condition.compare`: the static exec in + true/false ports,
 * with the `source` and `value` DATA input port schemas rewritten from the
 * `type` config and any connected edge.
 */
private fun conditionEffectivePorts(workflow: Workflow, node: WorkflowNode): List<Port> {
    val sourceSchema = conditionSourceSchema(workflow, node)
    return listOf(
        execIn(),
        execOut(ExecPorts.TRUE),
        execOut(ExecPorts.FALSE),
        dataPort(CONDITION_SOURCE_IN, Direction.IN, sourceSchema, label = "Source"),
        dataPort(CONDITION_VALUE_IN, Direction.IN, sourceSchema, label = "Compare against"),
    )
}

/**
 * The schema of the `source` DATA input on `condition.compare`:
 *  - pinned type → the chosen [ItemSchema.Primitive];
 *  - auto + connected → the connected source's schema (struct/primitive);
 *  - auto + unconnected → [ItemSchema.Wildcard] (accepts anything).
 */
private fun conditionSourceSchema(workflow: Workflow, node: WorkflowNode): ItemSchema {
    val type = conditionType(node)
    if (type != ComparisonType.AUTO) return type.schema ?: ItemSchema.Wildcard
    return resolveInputSchema(workflow, node, CONDITION_SOURCE_IN) ?: ItemSchema.Wildcard
}

/**
 * Resolves the [ItemSchema] of the item arriving on the DATA input port named
 * [inputPortName] of [node], by following the incoming DATA edge back to its
 * source port. Used by `action.break` (struct IN) and `condition.compare`
 * (source IN) to derive their dynamic port / config schemas. Returns null
 * when no edge is wired or the source has no schema.
 */
@Suppress("ReturnCount") // Null-guards on the optional edge/node/def path are idiomatic here.
private fun resolveInputSchema(
    workflow: Workflow,
    node: WorkflowNode,
    inputPortName: PortName,
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
