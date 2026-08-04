@file:Suppress("TooManyFunctions")

package com.example.ottomatic.domain.registry

import com.example.ottomatic.core.model.ConfigKey
import com.example.ottomatic.core.model.NodeId
import com.example.ottomatic.domain.model.Direction
import com.example.ottomatic.domain.model.ANY_LIST
import com.example.ottomatic.domain.model.ANY_STRUCT
import com.example.ottomatic.domain.model.ExecPorts
import com.example.ottomatic.domain.model.NodeTypeDefinition
import com.example.ottomatic.core.model.NodeTypeId
import com.example.ottomatic.domain.model.PortSpec
import com.example.ottomatic.domain.model.Port
import com.example.ottomatic.domain.model.PortKind
import com.example.ottomatic.core.model.PortName
import com.example.ottomatic.domain.model.ValueSource
import com.example.ottomatic.domain.model.VariableDeclaration
import com.example.ottomatic.domain.model.VariableRef
import com.example.ottomatic.domain.model.Workflow
import com.example.ottomatic.domain.model.WorkflowNode
import com.example.ottomatic.domain.model.config.ComparisonOperator
import com.example.ottomatic.domain.model.config.ComparisonType
import com.example.ottomatic.domain.model.config.ValueType
import com.example.ottomatic.domain.model.execIn
import com.example.ottomatic.domain.model.execOut
import com.example.ottomatic.domain.model.schema.DateTime
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

/** typeId of the condition-controlled loop. */
val WHILE_TYPE_ID = NodeTypeId("action.while")

/**
 * The nodes whose config *is* a comparison.
 *
 * `action.if` and `action.while` ask the same question and differ only in what they
 * do with the answer — branch, or go round again. Everything the question needs is
 * therefore shared: the two `source`/`value` ports, the graph-narrowed form, and
 * `evaluateCompare` itself. Keeping this a set rather than repeating the typeId
 * check is what stops the two drifting into two slightly different comparisons.
 */
val COMPARISON_TYPE_IDS = setOf(IF_TYPE_ID, WHILE_TYPE_ID)

/** The data input port on `action.if` carrying the value to inspect. */
val IF_SOURCE_IN = PortName("source")

/** The data input port on `action.if` carrying the value to compare against. */
val IF_VALUE_IN = PortName("value")

/** The DATA output port every transform emits on. */
val TRANSFORM_OUT = PortName("value")

/** typeId of the conversion transform — the node the editor inserts into a mismatched wire. */
val CONVERT_TYPE_ID = NodeTypeId("transform.convert")

/** The data input port on `transform.convert` carrying the value to convert. */
val CONVERT_IN = PortName("in")

/** The config key naming a conversion's target type (a [ValueType]). */
val CONVERT_TO_KEY = ConfigKey("to")

/** typeId of the JSON reading transform. */
val JSON_READ_TYPE_ID = NodeTypeId("transform.json_read")

/** The config key naming a JSON read's result type (a [ValueType]). */
val JSON_READ_TYPE_KEY = ConfigKey("type")

/** The config key saying a JSON read lands on an array, making its output a list. */
val JSON_READ_LIST_KEY = ConfigKey("list")

/** typeId of the for-each loop — the graph's only iteration over a list. */
val FOR_EACH_TYPE_ID = NodeTypeId("action.for_each")

/** The data input port on `action.for_each` carrying the list to walk. */
val FOR_EACH_LIST_IN = PortName("list")

/** The data output port on `action.for_each` carrying the current element. */
val FOR_EACH_ITEM_OUT = PortName("item")

/** The data output port carrying the current position, on both loop nodes. */
val LOOP_INDEX_OUT = PortName("index")

/** typeId of the count-based loop. */
val REPEAT_TYPE_ID = NodeTypeId("action.repeat")

/** The data input port every list transform reads its list from. */
val LIST_IN = PortName("list")

/** typeId of the transform reading one element out of a list. */
val LIST_ITEM_TYPE_ID = NodeTypeId("transform.list_item")

/** typeId of the transform reordering a list. */
val LIST_SORT_TYPE_ID = NodeTypeId("transform.list_sort")

/** typeId of the transform taking a run of elements out of a list. */
val LIST_SLICE_TYPE_ID = NodeTypeId("transform.list_slice")

/** typeId of the text building transform. */
val TEXT_TYPE_ID = NodeTypeId("transform.text")

/** typeId of the scripting action — the graph's escape hatch into real code. */
val SCRIPT_TYPE_ID = NodeTypeId("action.script")

/** typeId of the dialog that is only acknowledged. */
val DIALOG_MESSAGE_TYPE_ID = NodeTypeId("action.dialog_message")

/** typeId of the yes/no dialog. */
val DIALOG_CONFIRM_TYPE_ID = NodeTypeId("action.dialog_confirm")

/** typeId of the dialog asking for one typed value. */
val DIALOG_INPUT_TYPE_ID = NodeTypeId("action.dialog_input")

/** typeId of the dialog offering a list of options. */
val DIALOG_CHOICE_TYPE_ID = NodeTypeId("action.dialog_choice")

/**
 * The nodes that put a question to the user.
 *
 * Grouped for the reason [COMPARISON_TYPE_IDS] is: all four resolve their ports
 * through one function, because the rule that hides an unreachable `timed_out`
 * branch has to be the same rule on every one of them.
 */
val DIALOG_TYPE_IDS = setOf(
    DIALOG_MESSAGE_TYPE_ID,
    DIALOG_CONFIRM_TYPE_ID,
    DIALOG_INPUT_TYPE_ID,
    DIALOG_CHOICE_TYPE_ID,
)

/** The config key holding how long a dialog waits, in seconds. Zero means forever. */
val DIALOG_TIMEOUT_KEY = ConfigKey("timeoutSeconds")

/** The config key naming what `action.dialog_input` asks for (a [ValueType]). */
val DIALOG_INPUT_TYPE_KEY = ConfigKey("answerType")

/** The DATA output port on `action.dialog_input` carrying what the user typed. */
val DIALOG_VALUE_OUT = PortName("value")

/** The DATA output port on `action.dialog_choice` carrying the option picked. */
val DIALOG_CHOICE_OUT = PortName("choice")

/** The DATA output port on `action.dialog_choice` carrying that option's position. */
val DIALOG_INDEX_OUT = PortName("index")

/** typeId of the variable reader, whose output type its declaration states. */
val VARIABLE_VALUE_TYPE_ID = NodeTypeId("value.variable")

/** typeId of the variable writer, whose input port its declaration states. */
val SET_VARIABLE_TYPE_ID = NodeTypeId("action.set_variable")

/** The config key every variable-referencing node holds its ref spec in. */
val VARIABLE_REF_KEY = ConfigKey("name")

/** The DATA output port on `value.variable`. */
val VARIABLE_VALUE_OUT = PortName("value")

/** The DATA input port on `action.set_variable` carrying what to store. */
val SET_VARIABLE_VALUE_IN = PortName("value")

/** The config key holding a script's input ports (a list of [com.example.ottomatic.domain.model.PortSpec]). */
val SCRIPT_INPUTS_KEY = ConfigKey("inputs")

/** The config key holding a script's output ports (a list of [com.example.ottomatic.domain.model.PortSpec]). */
val SCRIPT_OUTPUTS_KEY = ConfigKey("outputs")

/** The config key of the comparison's field picker. */
val IF_FIELD_KEY = ConfigKey("field")

/** The config key of the comparison's operator picker. */
val IF_OPERATOR_KEY = ConfigKey("operator")

/** The config key of the comparison's type chooser. */
val IF_TYPE_CONFIG_KEY = ConfigKey("type")

/** The config key naming the comparison's source (a [ValueSource] spec). */
val IF_SOURCE_KEY = ConfigKey("source")

/**
 * What an untouched boolean comparison compares against.
 *
 * `true`, not `false`, because wiring a boolean into an `action.if` and leaving
 * it alone means "route on this value" — the identity comparison every other
 * node graph spells as a plain branch. Defaulting to `false` would silently
 * invert the source, which is the one answer nobody wires a boolean expecting.
 *
 * Read by the form (as this field's default) and by
 * [com.example.ottomatic.engine.evaluateCompare] (as what a blank literal
 * means), so the switch on screen and the branch taken at runtime cannot
 * disagree.
 */
const val IF_BOOLEAN_DEFAULT = "true"

/**
 * The effective ports for the placed [node] in [workflow]: the node type's
 * static ports, with dynamic rewrites for `action.break` (struct-derived
 * output ports), `action.if` (dynamic `source`/`value` input schemas) and the
 * adaptive transforms (config-driven output schema).
 */
fun effectivePorts(
    definition: NodeTypeDefinition,
    workflow: Workflow,
    node: WorkflowNode,
): List<Port> = effectivePorts(definition, workflow, node, visiting = emptySet())

/**
 * [effectivePorts] with the recursion guard made explicit.
 *
 * Resolution walks the graph in both directions — `action.break` and `action.if`
 * look *backwards* along an incoming edge, an adaptive transform looks *forwards*
 * to the port it feeds — so two dynamic nodes wired to each other would otherwise
 * ask each other for their schemas forever. [visiting] holds the nodes already
 * being resolved further up the call chain; re-entering one falls back to its
 * declared ports, which is exactly the "not known yet" answer the wildcard already
 * means.
 */
@Suppress("CyclomaticComplexMethod") // A flat dispatch table over typeIds, not branching logic.
private fun effectivePorts(
    definition: NodeTypeDefinition,
    workflow: Workflow,
    node: WorkflowNode,
    visiting: Set<NodeId>,
): List<Port> {
    if (node.id in visiting) return definition.ports
    val deeper = visiting + node.id
    return when (definition.typeId) {
        BREAK_TYPE_ID -> breakEffectivePorts(workflow, node, deeper)
        IF_TYPE_ID -> comparisonEffectivePorts(workflow, node, deeper, BRANCH_EXEC_PORTS)
        WHILE_TYPE_ID -> comparisonEffectivePorts(workflow, node, deeper, LOOP_EXEC_PORTS) +
            dataPort(LOOP_INDEX_OUT, Direction.OUT, ItemSchema.Primitive(Int::class), label = "Index")
        CONVERT_TYPE_ID -> typedTransformPorts(definition, workflow, node, CONVERT_TO_KEY, null, deeper)
        JSON_READ_TYPE_ID ->
            typedTransformPorts(definition, workflow, node, JSON_READ_TYPE_KEY, JSON_READ_LIST_KEY, deeper)
        SCRIPT_TYPE_ID -> scriptEffectivePorts(definition, node)
        in DIALOG_TYPE_IDS -> dialogEffectivePorts(definition, workflow, node, deeper)
        VARIABLE_VALUE_TYPE_ID -> variableValuePorts(definition, workflow, node, deeper)
        SET_VARIABLE_TYPE_ID -> variableWritePorts(definition, workflow, node)
        FOR_EACH_TYPE_ID -> forEachEffectivePorts(workflow, node, deeper)
        LIST_ITEM_TYPE_ID -> listOutputPorts(definition, workflow, node, deeper) { it.element }
        LIST_SORT_TYPE_ID, LIST_SLICE_TYPE_ID -> listOutputPorts(definition, workflow, node, deeper) { it }
        else -> definition.ports
    }
}

/**
 * The element type of the list wired into [port] of [node], or null when nothing is
 * wired or what is wired is not a list.
 *
 * The shared half of every list-aware resolution below: `action.for_each` and
 * `transform.list_item` want `.element`, `transform.list_sort` and
 * `transform.list_slice` want the list itself, and all four ask the same question
 * first.
 */
private fun wiredListSchema(
    workflow: Workflow,
    node: WorkflowNode,
    port: PortName,
    visiting: Set<NodeId>,
): ItemSchema.ListSchema? = resolveInputSchema(workflow, node, port, visiting) as? ItemSchema.ListSchema

/**
 * Ports for `action.for_each`: the fixed exec and index ports, plus an `item`
 * output typed from whatever list is wired in.
 *
 * The exec ports are re-declared by hand for the same reason [ifEffectivePorts]
 * does it — this replaces the declared port list wholesale, so anything left out
 * disappears from the card.
 */
private fun forEachEffectivePorts(workflow: Workflow, node: WorkflowNode, visiting: Set<NodeId>): List<Port> {
    val element = wiredListSchema(workflow, node, FOR_EACH_LIST_IN, visiting)?.element ?: ItemSchema.Wildcard
    return listOf(
        execIn(),
        execOut(ExecPorts.BODY, ExecPorts.BODY_LABEL),
        execOut(ExecPorts.COMPLETED, ExecPorts.COMPLETED_LABEL),
        dataPort(FOR_EACH_LIST_IN, Direction.IN, ANY_LIST, label = "List"),
        dataPort(FOR_EACH_ITEM_OUT, Direction.OUT, element, label = "Item"),
        dataPort(LOOP_INDEX_OUT, Direction.OUT, ItemSchema.Primitive(Int::class), label = "Index"),
    )
}

/**
 * Ports for a list transform whose output type follows its input: [element] picks
 * what the output carries, given the wired list's schema.
 *
 * Unlike [typedTransformPorts] there is nothing to narrow against the consumer —
 * the answer comes from upstream, and a consumer that wants something else has to
 * convert visibly like everyone else.
 */
private fun listOutputPorts(
    definition: NodeTypeDefinition,
    workflow: Workflow,
    node: WorkflowNode,
    visiting: Set<NodeId>,
    element: (ItemSchema.ListSchema) -> ItemSchema,
): List<Port> {
    val schema = wiredListSchema(workflow, node, LIST_IN, visiting)?.let(element) ?: ItemSchema.Wildcard
    return definition.ports.map { port ->
        if (port.name == TRANSFORM_OUT && port.direction == Direction.OUT) port.copy(schema = schema) else port
    }
}

/**
 * Ports for `action.script`: its declared exec ports plus a DATA port per entry
 * of its own `inputs` and `outputs` config.
 *
 * The only dynamic node that walks no edges. `action.break` asks what is wired
 * *into* it and an adaptive transform asks what it feeds, but a script's ports
 * are stated outright by the user, so there is nothing to resolve and no
 * recursion to guard against.
 *
 * Both sides are built the same way, which is the point: an input and an output
 * are one [PortSpec] seen from opposite directions. A port only exists once it
 * has been declared, so a script that reads nothing has no input handles at all
 * rather than three unused ones, and a typed port carries its own colour and its
 * own type check instead of a wildcard that accepts anything.
 *
 * A name colliding with a declared port is dropped rather than shadowing it:
 * silently rebinding an exec port to a script's value would be a far worse
 * surprise than a missing handle. Inputs and outputs may share a name — ports
 * are unique per direction, not per node.
 */
private fun scriptEffectivePorts(definition: NodeTypeDefinition, node: WorkflowNode): List<Port> {
    val taken = definition.ports.mapTo(mutableSetOf()) { it.name.value }
    fun ports(specs: List<PortSpec>, direction: Direction) = specs
        .filterNot { it.name in taken }
        .map { spec -> dataPort(PortName(spec.name), direction, spec.schema, label = spec.name) }
    return definition.ports +
        ports(PortSpec.parse(node.config[SCRIPT_INPUTS_KEY]), Direction.IN) +
        ports(PortSpec.parseOutputs(node.config[SCRIPT_OUTPUTS_KEY]), Direction.OUT)
}

/**
 * Ports for the dialog nodes: the declared ports, minus the `timed_out` branch
 * when no timeout is configured, plus `action.dialog_input`'s answer port retyped
 * to whatever it asks for.
 *
 * **Why the branch is hidden rather than simply always there.** All three routes
 * are declared statically on [com.example.ottomatic.engine.ExecOutputs.DECISION],
 * because separating "the user said no" from "nobody was there" is the point of
 * having a third one at all. But a dialog that waits forever can never take it,
 * and an exec port that cannot fire is an invitation to wire a branch that will
 * never run. So the *declaration* is unconditional and the *handle* follows the
 * config — the same rule `action.script` keeps, where a port exists once it has
 * been asked for.
 *
 * The consequence is that clearing the timeout can strand an edge on a port that
 * is no longer drawn. `GraphValidator` will not catch it — exec edges validate
 * against the static declaration — so `GraphEditorViewModel.pruneRetypedEdges`
 * drops it at the moment the config changes.
 */
private fun dialogEffectivePorts(
    definition: NodeTypeDefinition,
    workflow: Workflow,
    node: WorkflowNode,
    visiting: Set<NodeId>,
): List<Port> {
    val waits = (node.config[DIALOG_TIMEOUT_KEY]?.toIntOrNull() ?: 0) > 0
    return definition.ports
        .filterNot { it.name == ExecPorts.TIMED_OUT && !waits }
        .map { port ->
            if (port.name != DIALOG_VALUE_OUT || port.direction != Direction.OUT) {
                port
            } else {
                val type = configuredValueType(node, DIALOG_INPUT_TYPE_KEY)
                port.copy(schema = narrowedToConsumer(type, workflow, node, DIALOG_VALUE_OUT, visiting))
            }
        }
}

/**
 * Ports for a transform whose output type is chosen in its own config
 * (`transform.convert`, `transform.json_read`): the declared ports with the
 * wildcard [TRANSFORM_OUT] port retyped to the selected [ValueType].
 *
 * When the output already feeds a port of a *narrower* primitive in the same
 * family — a `Long` counter, a `Float` accuracy — that consumer's schema wins.
 * "Whole number" is one choice in the form because nobody wants to pick between
 * Int and Long, but the edge still has to type-check exactly, and the conversion
 * is total either way.
 *
 * [listKey], when the node has one, turns the result into a list of that family.
 * The consumer narrowing is skipped in that case: it exists to pin one primitive
 * out of a family against a single consuming port, and the family's own default is
 * the only sensible element type for a list.
 */
@Suppress("LongParameterList") // Two config keys and the recursion guard travel together.
private fun typedTransformPorts(
    definition: NodeTypeDefinition,
    workflow: Workflow,
    node: WorkflowNode,
    typeKey: ConfigKey,
    listKey: ConfigKey?,
    visiting: Set<NodeId>,
): List<Port> {
    val selected = configuredValueType(node, typeKey)
    val asList = listKey != null && node.config[listKey]?.toBooleanStrictOrNull() == true
    val schema = if (asList) {
        ItemSchema.ListSchema(selected.schema)
    } else {
        narrowedToConsumer(selected, workflow, node, TRANSFORM_OUT, visiting)
    }
    return definition.ports.map { port ->
        if (port.name == TRANSFORM_OUT && port.direction == Direction.OUT) port.copy(schema = schema) else port
    }
}

/**
 * [type]'s schema, narrowed to the consuming port when that port asks for a
 * *narrower* primitive of the same family — a `Long` counter, a `Float` accuracy.
 *
 * "Whole number" is one choice in a form because nobody wants to pick between Int
 * and Long, but the edge still has to type-check exactly. Written once here because
 * both things that announce a config-chosen type — an adaptive transform and
 * `value.variable` — have to answer the question the same way, or the same wire
 * would be legal from one and refused from the other.
 */
private fun narrowedToConsumer(
    type: ValueType,
    workflow: Workflow,
    node: WorkflowNode,
    port: PortName,
    visiting: Set<NodeId>,
): ItemSchema = resolveOutputSchema(workflow, node, port, visiting)?.takeIf { type.covers(it) } ?: type.schema

/**
 * The declaration the ref [spec] names, local or global, or null when nothing is
 * chosen or the declaration has been deleted.
 *
 * The single answer to "which variable is this node talking about?", shared by the
 * two port resolutions below, by `GraphValidator`'s dangling-ref warning, by the
 * legacy repair and by the config form's picker. A second copy of this would be a
 * second opinion about what a deleted variable means.
 */
fun declarationFor(workflow: Workflow, spec: String?): VariableDeclaration? =
    when (val ref = VariableRef.parse(spec.orEmpty())) {
        null -> null
        is VariableRef.Local -> workflow.variable(ref.id)
        is VariableRef.Global -> GlobalVariables.byId(ref.id)
    }

/**
 * Ports for `value.variable`: its one DATA output, typed from the declaration it
 * reads.
 *
 * This is what makes a declared type worth having. A counter declared "Whole
 * number" drops straight into a numeric port, where a text-only variable used to
 * need a `transform.convert` in every wire leaving it — and because the type is
 * *declared* rather than guessed, the conversion `value.variable` performs on the
 * stored text has something visible standing behind it.
 *
 * An undeclared or unchosen ref leaves the wildcard alone. That is the honest
 * answer — "not known yet", which is what a wildcard already means everywhere else
 * — and `GraphValidator` is what says so out loud.
 */
private fun variableValuePorts(
    definition: NodeTypeDefinition,
    workflow: Workflow,
    node: WorkflowNode,
    visiting: Set<NodeId>,
): List<Port> {
    val type = declarationFor(workflow, node.config[VARIABLE_REF_KEY])?.type ?: return definition.ports
    val schema = narrowedToConsumer(type, workflow, node, VARIABLE_VALUE_OUT, visiting)
    return definition.ports.map { port ->
        if (port.name == VARIABLE_VALUE_OUT && port.direction == Direction.OUT) port.copy(schema = schema) else port
    }
}

/**
 * Ports for `action.set_variable`: its `value` DATA input, typed from the
 * declaration it writes.
 *
 * The mirror of [variableValuePorts], and it walks no edges — there is nothing
 * downstream of an input to narrow against, and the type comes from the node's own
 * config either way.
 *
 * What it types is the **connection**, not the storage: a number wired at a text
 * variable is a refused drop that picks up a visible `transform.convert`, while
 * what actually lands in the store is still the flat text `NodeSchema.decode`
 * produced through `asText()`.
 */
private fun variableWritePorts(
    definition: NodeTypeDefinition,
    workflow: Workflow,
    node: WorkflowNode,
): List<Port> {
    val type = declarationFor(workflow, node.config[VARIABLE_REF_KEY]) ?: return definition.ports
    return definition.ports.map { port ->
        if (port.name == SET_VARIABLE_VALUE_IN && port.direction == Direction.IN) {
            port.copy(schema = type.type.schema)
        } else {
            port
        }
    }
}

/** The [ValueType] named by [node]'s [typeKey] config, defaulting to text. */
private fun configuredValueType(node: WorkflowNode, typeKey: ConfigKey): ValueType {
    val raw = node.config[typeKey]?.takeIf { it.isNotBlank() } ?: return ValueType.TEXT
    return runCatching { ValueType.valueOf(raw) }.getOrDefault(ValueType.TEXT)
}

/**
 * The [ItemSchema] of the port that consumes [outputPortName] of [node], by
 * following the outgoing DATA edge forward. The mirror of [resolveInputSchema];
 * returns null when nothing is wired, or when several consumers disagree — a
 * conversion feeding two differently-typed ports has no single right answer, so it
 * falls back to its family default and the second edge is the one that has to
 * convert again.
 */
private fun resolveOutputSchema(
    workflow: Workflow,
    node: WorkflowNode,
    outputPortName: PortName,
    visiting: Set<NodeId>,
): ItemSchema? {
    val edges = workflow.dataConnections.filter { it.fromNodeId == node.id && it.fromPort == outputPortName }
    val schemas = edges.mapNotNull { edge ->
        val targetNode = workflow.node(edge.toNodeId) ?: return@mapNotNull null
        val targetDef = NodeTypeRegistry.byId(targetNode.typeId) ?: return@mapNotNull null
        // The target's *effective* ports: `action.if` only knows it wants a Long
        // once its own type is resolved. [visiting] stops that walking back here.
        effectivePorts(targetDef, workflow, targetNode, visiting).firstOrNull {
            it.name == edge.toPort && it.kind == PortKind.DATA && it.direction == Direction.IN
        }?.schema
    }.distinct()
    return schemas.singleOrNull()
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
    val narrowed = if (definition.typeId in COMPARISON_TYPE_IDS) {
        compareSchema(declared, workflow, node)
    } else {
        declared
    }
    return narrowed.visibleFor(node.config).takeIf { it.fields.isNotEmpty() }
}

/**
 * The sources a comparison may inspect, as form options: whatever is wired into its
 * own `source` port, plus every value node **except `value.variable`**.
 *
 * Value nodes are otherwise always offered because they are pure — they can be read
 * on demand with no edge and no execution position, so choosing one needs nothing
 * else on the canvas.
 *
 * `value.variable` is the one that cannot honour that. A `val:` source is read with
 * *no config at all* (`resolveValueSource` passes an empty map), and every other
 * value gives the same answer wherever it is read; this one's answer is entirely a
 * matter of which variable was chosen, which the spec has nowhere to carry. Offering
 * it would offer a comparison that silently never matches — the worst kind of
 * affordance, and one that used to be there. Comparing a variable means wiring the
 * node into `source`, which is one drag.
 *
 * The alternative was a third spec form (`val:value.variable:<ref>`), growing the
 * persisted grammar and `resolveValueSource`'s signature for a single node.
 */
private fun sourceOptions(): List<ConfigOption> =
    listOf(ConfigOption(ValueSource.WIRED_SPEC, "Wired input")) +
        ValueRegistry.all()
            .map { it.definition.nodeType }
            .filterNot { it.typeId == VARIABLE_VALUE_TYPE_ID }
            .map { ConfigOption(ValueSource.valueSpec(it.typeId), it.displayName) }

/**
 * The [ItemSchema] of the value the [spec] source yields on [node], or null when it
 * cannot be determined.
 *
 * This is the single place that decides what a source's type is, so the operator
 * list, the struct-field picker and the compare-against literal all narrow off one
 * answer — and a `val:` source gets a correctly typed form without anything being
 * wired.
 */
private fun sourceSchema(
    spec: String,
    workflow: Workflow,
    node: WorkflowNode,
): ItemSchema? = when (val source = ValueSource.parse(spec)) {
    // A fresh walk from the config form rather than from a port resolution, so the
    // guard starts here — with this node already in it, since resolving what feeds
    // it can lead back to a transform that asks what this node wants.
    ValueSource.Wired -> resolveInputSchema(workflow, node, IF_SOURCE_IN, visiting = setOf(node.id))
    is ValueSource.Value -> ValueRegistry.byId(source.typeId)?.definition?.nodeType?.ports?.firstOrNull()?.schema
}

/**
 * Drops the fields whose [ConfigField.visibleWhen] rule [config] does not
 * satisfy. The controlling value is read from [config], falling back to the
 * controlling *field's* declared default — so a node the user has never touched
 * shows the fields belonging to its default mode rather than none of them.
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
 * the graph:
 *  - `source` becomes an ENUM over [sourceOptions];
 *  - the field picker is dropped unless the inspected value is a struct, and
 *    otherwise becomes an ENUM of that struct's field names;
 *  - the operator list shrinks to those valid for the inspected type;
 *  - the compare-against literal is retyped to the inspected primitive.
 *
 * The `type` chooser survives only when the source is the node's own wired port:
 * pinning a primitive exists to make an *unconnected* port configurable, and a
 * value node already knows its own type.
 */
private fun compareSchema(
    declared: NodeConfigSchema,
    workflow: Workflow,
    node: WorkflowNode,
): NodeConfigSchema {
    val sources = sourceOptions()
    val spec = node.config[IF_SOURCE_KEY] ?: sources.firstOrNull()?.value.orEmpty()
    val pinned = ValueSource.parse(spec) == ValueSource.Wired
    val structFields = compareStructFields(spec, workflow, node)
    val inspected = inspectedSchema(spec, workflow, node, structFields)
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
            ConfigKey(IF_VALUE_IN.value) -> literalTypeFor(inspected).let { literal ->
                ConfigField(field.key, field.label, literal, literalDefaultFor(literal, field.defaultValue))
            }
            else -> field
        }
    }
    // The node's own id, not `action.if`'s: `action.while` narrows through here too,
    // and stamping the wrong typeId onto its form would have the editor look its
    // fields up against the wrong node.
    return NodeConfigSchema(typeId = declared.typeId, fields = fields)
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
    workflow: Workflow,
    node: WorkflowNode,
): List<String>? {
    if (comparisonType(node.config) != ComparisonType.AUTO) return null
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
    workflow: Workflow,
    node: WorkflowNode,
    structFields: List<String>?,
): ItemSchema {
    val fallback = ItemSchema.Primitive(String::class)
    val type = comparisonType(node.config)
    if (type != ComparisonType.AUTO) return type.schema ?: fallback
    val resolved = sourceSchema(spec, workflow, node)
    return if (structFields != null && resolved is ItemSchema.Object) {
        val selected = node.config[IF_FIELD_KEY]?.takeIf { it in structFields } ?: structFields.first()
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

/**
 * The default a compare-against literal shows before the user touches it.
 *
 * Only a boolean needs one of its own: its editor is a switch, which always
 * renders *some* state, so the declared `""` would put a definite-looking "off"
 * on screen for a value the comparison does not read as false. Every other
 * editor renders blank as blank, which is honest.
 */
private fun literalDefaultFor(type: ConfigFieldType<*>, declared: String): String =
    if (type == ConfigFieldType.BOOL) IF_BOOLEAN_DEFAULT else declared

/** The form type of a compare-against literal of the given [schema]. */
private fun literalTypeFor(schema: ItemSchema?): ConfigFieldType<*> =
    if (schema is ItemSchema.Primitive) {
        when (schema.kClass) {
            Int::class -> ConfigFieldType.INT
            Long::class, Double::class, Float::class -> ConfigFieldType.DOUBLE
            Boolean::class -> ConfigFieldType.BOOL
            DateTime::class -> ConfigFieldType.DATE_TIME
            else -> ConfigFieldType.STR
        }
    } else {
        ConfigFieldType.STR
    }

/**
 * Ports for an `action.break` node: base (exec in/out + the struct IN port, which
 * accepts any object and nothing else) plus one DATA OUT per field of the struct
 * connected to [BREAK_STRUCT_IN].
 */
private fun breakEffectivePorts(workflow: Workflow, node: WorkflowNode, visiting: Set<NodeId>): List<Port> {
    val base = listOf(
        execIn(),
        execOut(),
        dataPort(BREAK_STRUCT_IN, Direction.IN, ANY_STRUCT, label = "Struct"),
    )
    val schema = resolveInputSchema(workflow, node, BREAK_STRUCT_IN, visiting) as? ItemSchema.Object ?: return base
    return base + schema.fields.map { (name, fieldSchema) -> dataPort(PortName(name), Direction.OUT, fieldSchema) }
}

/** `action.if`'s exec ports: it routes the answer. */
private val BRANCH_EXEC_PORTS: List<Port> = listOf(execIn(), execOut(ExecPorts.TRUE), execOut(ExecPorts.FALSE))

/** `action.while`'s exec ports: it repeats on the answer. */
private val LOOP_EXEC_PORTS: List<Port> = listOf(
    execIn(),
    execOut(ExecPorts.BODY, ExecPorts.BODY_LABEL),
    execOut(ExecPorts.COMPLETED, ExecPorts.COMPLETED_LABEL),
)

/**
 * Effective ports for a comparison node ([COMPARISON_TYPE_IDS]): [execPorts], plus
 * the `source` and `value` DATA inputs rewritten from the `type` config and any
 * connected edge.
 *
 * The exec ports are a parameter rather than hard-coded because that is the *only*
 * thing `action.if` and `action.while` disagree about — everything to do with the
 * comparison itself is identical, and a second copy of this function would be two
 * places for the source schema to be resolved differently.
 *
 * They have to be listed at all because this replaces the declared port set
 * wholesale: anything left out simply vanishes from the card.
 */
private fun comparisonEffectivePorts(
    workflow: Workflow,
    node: WorkflowNode,
    visiting: Set<NodeId>,
    execPorts: List<Port>,
): List<Port> {
    val schema = ifSourcePortSchema(workflow, node, visiting)
    return execPorts + listOf(
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
private fun ifSourcePortSchema(workflow: Workflow, node: WorkflowNode, visiting: Set<NodeId>): ItemSchema {
    val type = comparisonType(node.config)
    if (type != ComparisonType.AUTO) return type.schema ?: ItemSchema.Wildcard
    return resolveInputSchema(workflow, node, IF_SOURCE_IN, visiting) ?: ItemSchema.Wildcard
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
    visiting: Set<NodeId>,
): ItemSchema? {
    val edge = workflow.incomingData(node.id, inputPortName).firstOrNull() ?: return null
    val sourceNode = workflow.node(edge.fromNodeId) ?: return null
    val sourceDef = NodeTypeRegistry.byId(sourceNode.typeId) ?: return null
    val sourcePorts = if (sourceDef.hasDynamicPorts) {
        effectivePorts(sourceDef, workflow, sourceNode, visiting)
    } else {
        sourceDef.ports
    }
    return sourcePorts.firstOrNull {
        it.name == edge.fromPort && it.kind == PortKind.DATA && it.direction == Direction.OUT
    }?.schema
}
