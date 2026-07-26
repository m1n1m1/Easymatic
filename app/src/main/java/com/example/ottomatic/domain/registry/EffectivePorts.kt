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
import com.example.ottomatic.domain.model.ValueSource
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
 *  2. **Dynamic comparison ports + config** — `action.if` rewrites its
 *     `source`/`value` DATA input port schemas, and *narrows* the config form
 *     derived from `CompareConfig` (see [effectiveConfigSchema]): the source
 *     becomes a dropdown of the sources available here, the field picker becomes a
 *     typed dropdown of the struct's fields, the operator list shrinks to those
 *     valid for the selected field's type, and the compare-against literal is
 *     retyped to match.
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

/** typeId of the adaptive comparison action — the graph's only conditional branch. */
val IF_TYPE_ID = NodeTypeId("action.if")

/** The data input port on `action.if` carrying the value to inspect. */
val IF_SOURCE_IN = PortName("source")

/** The data input port on `action.if` carrying the value to compare against. */
val IF_VALUE_IN = PortName("value")

/** The config key of the comparison's field picker. */
val IF_FIELD_KEY = ConfigKey("field")

/** The config key of the comparison's operator picker. */
val IF_OPERATOR_KEY = ConfigKey("operator")

/** The config key of the comparison's type chooser. */
val IF_TYPE_CONFIG_KEY = ConfigKey("type")

/** The config key naming the comparison's source (a [ValueSource] spec). */
val IF_SOURCE_KEY = ConfigKey("source")

/**
 * The effective ports for the placed [node] in [workflow]: the node type's
 * static ports, with dynamic rewrites for `action.break` (struct-derived
 * output ports) and `action.if` (dynamic `source`/`value` input schemas).
 */
fun effectivePorts(
    definition: NodeTypeDefinition,
    workflow: Workflow,
    node: WorkflowNode,
): List<Port> = when (definition.typeId) {
    BREAK_TYPE_ID -> breakEffectivePorts(workflow, node)
    IF_TYPE_ID -> ifEffectivePorts(workflow, node)
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
 * from the node's config class, graph-narrowed for `action.if`, then
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
    val narrowed = if (definition.typeId == IF_TYPE_ID) {
        compareSchema(declared, sourceOptions(workflow, node, attached = false), node.config, workflow, node)
    } else {
        declared
    }
    return narrowed.visibleFor(node.config).takeIf { it.fields.isNotEmpty() }
}

/**
 * The effective [NodeConfigSchema] for an [AttachedCondition] on [host]: the same
 * derivation as [effectiveConfigSchema], but for a gate that has no node and
 * therefore no incoming edges of its own.
 *
 * Both placements run the *same* comparison and the *same* narrowing
 * ([compareSchema]); they differ only in what the source dropdown offers. A placed
 * `action.if` can be wired, so it offers its own `source` port plus every value
 * node. An attached gate has nothing wired to it, so it offers the host's own DATA
 * input ports — the items it will actually be handed at runtime (see
 * [com.example.ottomatic.engine.conditionsPass]) — plus every value node. Reading a
 * value node needs no edge at all, which is what makes a one-tap gate possible.
 *
 * Returns null when the gate has no configurable fields.
 */
fun effectiveConditionSchema(
    workflow: Workflow,
    host: WorkflowNode,
    attached: AttachedCondition,
): NodeConfigSchema? {
    val declared = ConfigSchemaRegistry.byId(IF_TYPE_ID) ?: return null
    val narrowed = compareSchema(
        declared = declared,
        sources = sourceOptions(workflow, host, attached = true),
        config = attached.config,
        workflow = workflow,
        node = host,
    )
    return narrowed.visibleFor(attached.config).takeIf { it.fields.isNotEmpty() }
}

/**
 * The sources a comparison on [node] may inspect, as form options.
 *
 * Value nodes are always offered: being pure, they can be read on demand without an
 * edge or an execution position. What differs is the *other* end — an [attached]
 * gate offers its host's DATA input ports, while a placed node offers its own wired
 * `source` port.
 */
fun sourceOptions(
    workflow: Workflow,
    node: WorkflowNode,
    attached: Boolean,
): List<ConfigOption> {
    val hostPorts = if (attached) {
        NodeTypeRegistry.byId(node.typeId)
            ?.let { effectiveInputPorts(it, workflow, node) }
            ?.filter { it.kind == PortKind.DATA }
            .orEmpty()
            .map { ConfigOption(ValueSource.hostSpec(it.name), it.label) }
    } else {
        listOf(ConfigOption(ValueSource.WIRED_SPEC, "Wired input"))
    }
    val values = ValueRegistry.all().map { value ->
        val definition = value.definition.nodeType
        ConfigOption(ValueSource.valueSpec(definition.typeId), definition.displayName)
    }
    return hostPorts + values
}

/**
 * The [ItemSchema] of the value the [spec] source yields on [node], or null when it
 * cannot be determined.
 *
 * This is the single place that decides what a source's type is, so the operator
 * list, the struct-field picker and the compare-against literal all narrow off one
 * answer — and a `val:` source gets a correctly typed form without anything being
 * wired.
 */
fun sourceSchema(
    spec: String,
    workflow: Workflow,
    node: WorkflowNode,
): ItemSchema? = when (val source = ValueSource.parse(spec)) {
    ValueSource.Wired -> resolveInputSchema(workflow, node, IF_SOURCE_IN)
    is ValueSource.HostPort -> NodeTypeRegistry.byId(node.typeId)
        ?.let { effectiveInputPorts(it, workflow, node) }
        ?.firstOrNull { it.name == source.port }
        ?.schema
    is ValueSource.Value -> ValueRegistry.byId(source.typeId)?.definition?.nodeType?.ports?.firstOrNull()?.schema
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
 * Narrows the comparison's [declared] form (derived from `CompareConfig`) against
 * the graph — the single narrowing for both placements:
 *  - `source` becomes an ENUM over [sources];
 *  - the field picker is dropped unless the inspected value is a struct, and
 *    otherwise becomes an ENUM of that struct's field names;
 *  - the operator list shrinks to those valid for the inspected type;
 *  - the compare-against literal is retyped to the inspected primitive.
 *
 * The `type` chooser survives only when the source is the node's own wired port:
 * pinning a primitive exists to make an *unconnected* port configurable, and a
 * value node or host port already knows its own type.
 */
@Suppress("LongParameterList") // One narrowing serving both placements needs all of it.
private fun compareSchema(
    declared: NodeConfigSchema,
    sources: List<ConfigOption>,
    config: Map<ConfigKey, String>,
    workflow: Workflow,
    node: WorkflowNode,
): NodeConfigSchema {
    val spec = config[IF_SOURCE_KEY] ?: sources.firstOrNull()?.value.orEmpty()
    val pinned = ValueSource.parse(spec) == ValueSource.Wired
    val structFields = compareStructFields(spec, config, workflow, node)
    val inspected = inspectedSchema(spec, config, workflow, node, structFields)
    val operators = ComparisonOperator.forSchema(inspected)

    val fields = declared.fields.mapNotNull { field ->
        when (field.key) {
            // Pinning a primitive is meaningless unless there is a port to retype.
            IF_TYPE_CONFIG_KEY -> field.takeIf { pinned }
            IF_SOURCE_KEY -> sources.takeIf { it.isNotEmpty() }?.let { options ->
                ConfigField(
                    key = field.key,
                    label = "Source",
                    type = ConfigFieldType.ENUM(options),
                    defaultValue = options.first().value,
                )
            }
            IF_FIELD_KEY -> structFields?.let { field.asChoice(it, default = it.first()) }
            IF_OPERATOR_KEY -> field.asChoice(operators.map { it.name }, default = operators.first().name)
            ConfigKey(IF_VALUE_IN.value) ->
                ConfigField(field.key, field.label, literalTypeFor(inspected), field.defaultValue)
            else -> field
        }
    }
    return NodeConfigSchema(typeId = IF_TYPE_ID, fields = fields)
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
 * The field names of the struct the [spec] source yields, or null when the
 * inspected value is not a struct (or the type is pinned to a primitive).
 */
private fun compareStructFields(
    spec: String,
    config: Map<ConfigKey, String>,
    workflow: Workflow,
    node: WorkflowNode,
): List<String>? {
    if (comparisonType(config) != ComparisonType.AUTO) return null
    val resolved = sourceSchema(spec, workflow, node)
    return (resolved as? ItemSchema.Object)?.fields?.keys?.toList()?.takeIf { it.isNotEmpty() }
}

/**
 * The schema of the value the comparison actually inspects: the selected struct
 * field, the source's own primitive, the pinned primitive, or String as a fallback
 * so the form stays usable before anything is wired.
 */
private fun inspectedSchema(
    spec: String,
    config: Map<ConfigKey, String>,
    workflow: Workflow,
    node: WorkflowNode,
    structFields: List<String>?,
): ItemSchema {
    val fallback = ItemSchema.Primitive(String::class)
    val type = comparisonType(config)
    if (type != ComparisonType.AUTO) return type.schema ?: fallback
    val resolved = sourceSchema(spec, workflow, node)
    return if (structFields != null && resolved is ItemSchema.Object) {
        val selected = config[IF_FIELD_KEY]?.takeIf { it in structFields } ?: structFields.first()
        resolved.fields[selected] ?: fallback
    } else {
        resolved ?: fallback
    }
}

/** The configured [ComparisonType], defaulting to [ComparisonType.AUTO]. */
private fun comparisonType(config: Map<ConfigKey, String>): ComparisonType {
    val raw = config[IF_TYPE_CONFIG_KEY]?.takeIf { it.isNotBlank() } ?: return ComparisonType.AUTO
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
 * Effective ports for `action.if`: the static exec in + true/false ports, with the
 * `source` and `value` DATA input port schemas rewritten from the `type` config and
 * any connected edge.
 */
private fun ifEffectivePorts(workflow: Workflow, node: WorkflowNode): List<Port> {
    val schema = ifSourcePortSchema(workflow, node)
    return listOf(
        execIn(),
        execOut(ExecPorts.TRUE),
        execOut(ExecPorts.FALSE),
        dataPort(IF_SOURCE_IN, Direction.IN, schema, label = "Source"),
        dataPort(IF_VALUE_IN, Direction.IN, schema, label = "Compare against"),
    )
}

/**
 * The schema of the `source` DATA input port on `action.if`:
 *  - pinned type → the chosen [ItemSchema.Primitive];
 *  - auto + connected → the connected source's schema (struct/primitive);
 *  - auto + unconnected → [ItemSchema.Wildcard] (accepts anything).
 *
 * This describes the *port*, so it always reflects the wired edge — a node whose
 * config names a value node instead simply leaves the port unused.
 */
private fun ifSourcePortSchema(workflow: Workflow, node: WorkflowNode): ItemSchema {
    val type = comparisonType(node.config)
    if (type != ComparisonType.AUTO) return type.schema ?: ItemSchema.Wildcard
    return resolveInputSchema(workflow, node, IF_SOURCE_IN) ?: ItemSchema.Wildcard
}

/**
 * Resolves the [ItemSchema] of the item arriving on the DATA input port named
 * [inputPortName] of [node], by following the incoming DATA edge back to its
 * source port. Used by `action.break` (struct IN) and `action.if` (source IN) to
 * derive their dynamic port / config schemas. Returns null when no edge is wired or
 * the source has no schema.
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
