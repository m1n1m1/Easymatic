// One builder per node shape, and the shapes are the point: a value, an adaptive
// value, a transform, a raw transform, an adaptive transform, a trigger, a pulse
// trigger, an action, an effect, an adaptive action, a loop. Splitting the file
// would only make the set harder to read as a set.
@file:Suppress("TooManyFunctions")

package io.github.m1n1m1.easymatic.engine

import io.github.m1n1m1.easymatic.core.capabilities.DeviceCapability
import io.github.m1n1m1.easymatic.core.permissions.PermissionRequirement
import io.github.m1n1m1.easymatic.domain.model.DataOut
import io.github.m1n1m1.easymatic.domain.model.NodeCategory
import io.github.m1n1m1.easymatic.domain.model.NodeIcon
import io.github.m1n1m1.easymatic.domain.model.NodeKind
import io.github.m1n1m1.easymatic.domain.model.PlatformWarning
import io.github.m1n1m1.easymatic.domain.model.NodeTypeDefinition
import io.github.m1n1m1.easymatic.core.model.NodeTypeId
import io.github.m1n1m1.easymatic.domain.model.Port
import io.github.m1n1m1.easymatic.core.model.PortName
import io.github.m1n1m1.easymatic.domain.model.execIn
import io.github.m1n1m1.easymatic.domain.model.execOut
import io.github.m1n1m1.easymatic.domain.model.schema.Item
import io.github.m1n1m1.easymatic.domain.registry.NodeConfigSchema
import io.github.m1n1m1.easymatic.domain.registry.NodeSchema
import io.github.m1n1m1.easymatic.domain.registry.nodeSchema

/**
 * The single declaration of an action node, living in the action's own file.
 *
 * Everything that used to be spelled out by hand — the config form, the DATA
 * input ports, the decode step and the output encode step — is derived from two
 * things: the config class [I] (via [schema]) and the [output] port declaration.
 * A config key, a port name or a default therefore appears exactly once, in a
 * place where the Kotlin type system can check it.
 *
 * [nodeType] and [configSchema] are the derived views consumed by
 * [io.github.m1n1m1.easymatic.domain.registry.NodeTypeRegistry] and
 * [io.github.m1n1m1.easymatic.domain.registry.ConfigSchemaRegistry].
 */
@Suppress("LongParameterList") // A node definition is intentionally a flat declaration DSL.
class ActionNodeDefinition<I : Any, O : Any> @PublishedApi internal constructor(
    val typeId: NodeTypeId,
    val displayName: String,
    val description: String,
    val category: NodeCategory,
    val icon: NodeIcon,
    val schema: NodeSchema<I>,
    val output: DataOut<O>?,
    val execOutputs: ExecOutputs,
    val extraPorts: List<Port>,
    val hasDynamicPorts: Boolean,
    val permissions: List<PermissionRequirement> = emptyList(),
    val capabilities: List<DeviceCapability> = emptyList(),
    val platformWarnings: List<PlatformWarning> = emptyList(),
) {
    /** Static metadata view for [io.github.m1n1m1.easymatic.domain.registry.NodeTypeRegistry]. */
    val nodeType: NodeTypeDefinition
        get() = NodeTypeDefinition(
            typeId = typeId,
            displayName = displayName,
            description = description,
            kind = NodeKind.ACTION,
            category = category,
            ports = listOf(execIn()) + execOutputs.ports + schema.wiredPorts + extraPorts +
                listOfNotNull(output?.port),
            icon = icon,
            hasDynamicPorts = hasDynamicPorts,
            permissionRequirements = permissions,
            capabilities = capabilities,
            platformWarnings = platformWarnings,
        )

    /** Static config-form view for [io.github.m1n1m1.easymatic.domain.registry.ConfigSchemaRegistry]. */
    val configSchema: NodeConfigSchema?
        get() = schema.fields.takeIf { it.isNotEmpty() }?.let { NodeConfigSchema(typeId, it) }

    /** Encodes a typed action result onto this node's declared ports. */
    internal fun encode(result: NodeOutput<O>): EncodedNodeOutput = EncodedNodeOutput(
        execOut = listOf(routePort(result.route)),
        dataOut = output?.let { mapOf(it.name to it.encode(result.value)) }.orEmpty(),
        halt = result.halt,
    )

    /** Encodes an adaptive action's port-keyed result (see [RawAction]). */
    internal fun encodeDynamic(result: NodeOutput<Map<PortName, Item>>): EncodedNodeOutput = EncodedNodeOutput(
        execOut = listOf(routePort(result.route)),
        dataOut = result.value,
        halt = result.halt,
    )

    private fun routePort(route: ExecutionRoute): PortName {
        require(route in execOutputs.routes) {
            "Node $typeId declares $execOutputs exec outputs and cannot route to $route"
        }
        return route.portName
    }
}

/**
 * The single declaration of a trigger node, living in the trigger's own file.
 *
 * Like [ActionNodeDefinition], the config form and the output encoding are
 * derived from the config class [C] and the [output] port. Every trigger has a
 * single EXECUTION `out` port, so only the data output is declared.
 */
@Suppress("LongParameterList") // A node definition is intentionally a flat declaration DSL.
class TriggerNodeDefinition<C : Any, O : Any> @PublishedApi internal constructor(
    val typeId: NodeTypeId,
    val displayName: String,
    val description: String,
    val category: NodeCategory,
    val icon: NodeIcon,
    val schema: NodeSchema<C>,
    val output: DataOut<O>?,
    /**
     * Further DATA outputs projected out of the same event — see
     * [io.github.m1n1m1.easymatic.domain.model.derivedOut]. Each is filled from the very
     * value [output] carries, so they cannot disagree with it.
     */
    val extraOutputs: List<DataOut<O>> = emptyList(),
    val permissions: List<PermissionRequirement> = emptyList(),
    val capabilities: List<DeviceCapability> = emptyList(),
    /**
     * Whether this trigger's DATA outputs come from its own config and must be
     * resolved by [io.github.m1n1m1.easymatic.domain.registry.effectivePorts]. See
     * [adaptiveTriggerNode].
     */
    val hasDynamicPorts: Boolean = false,
) {
    /** Static metadata view for [io.github.m1n1m1.easymatic.domain.registry.NodeTypeRegistry]. */
    val nodeType: NodeTypeDefinition
        get() = NodeTypeDefinition(
            typeId = typeId,
            displayName = displayName,
            description = description,
            kind = NodeKind.TRIGGER,
            category = category,
            ports = listOf(execOut()) + listOfNotNull(output?.port) + extraOutputs.map { it.port },
            icon = icon,
            permissionRequirements = permissions,
            capabilities = capabilities,
            hasDynamicPorts = hasDynamicPorts,
        )

    /** Static config-form view for [io.github.m1n1m1.easymatic.domain.registry.ConfigSchemaRegistry]. */
    val configSchema: NodeConfigSchema?
        get() = schema.fields.takeIf { it.isNotEmpty() }?.let { NodeConfigSchema(typeId, it) }

    /** Encodes a typed trigger event onto this node's declared data output ports. */
    internal fun encode(value: O): Map<PortName, Item> =
        output?.let { mapOf(it.name to it.encode(value)) }.orEmpty() +
            extraOutputs.associate { it.name to it.encode(value) }
}

/**
 * The single declaration of a value node, living in the reader's own file.
 *
 * A value node is the graph's pull side: a side-effect-free reader of something
 * that is true *right now*. It declares exactly one port — the typed DATA output
 * [output] — and no EXECUTION ports at all, because it is never pulsed. Instead
 * it is read on demand, immediately before whichever node consumes it (see
 * [io.github.m1n1m1.easymatic.engine.WorkflowExecutor]), or named directly as an
 * `action.if` source with no edge drawn to it (see
 * [io.github.m1n1m1.easymatic.domain.model.ValueSource]).
 *
 * Purity is a contract, not a convention: `NodeDeclarationContractTest` asserts that
 * every value node declares no exec ports and no DATA inputs. Anything expensive,
 * failable or side-effecting is an action.
 *
 * A value **may** declare [permissions], and that is not a hole in the contract —
 * the contract is about ports and effects, not about grants. A grant does not make a
 * read expensive or repeat-unsafe; `value.wifi_network` needs `ACCESS_FINE_LOCATION`
 * to name a network and is otherwise as cheap as `value.battery`. Declaring it is
 * what makes the missing grant *visible*: the Problems panel, the Permissions screen
 * and the node's own card all walk these declarations, so a value that stayed silent
 * about its grant would read null forever with nothing anywhere saying why. What a
 * grant does not buy is permission to fail loudly — a read must still answer null and
 * let the consumer fall back.
 *
 * [outputPort] is the declared port and [output] the encoder, split for the reason
 * [TransformNodeDefinition] splits them: a value whose *type* comes from its own
 * config declares a wildcard port and no encoder, emitting its own [Item] instead.
 * `value.variable` is the one — a variable's type is stated by its declaration, not
 * by the node.
 */
@Suppress("LongParameterList") // A node definition is intentionally a flat declaration DSL.
class ValueNodeDefinition<C : Any, O : Any> @PublishedApi internal constructor(
    val typeId: NodeTypeId,
    val displayName: String,
    val description: String,
    val category: NodeCategory,
    val icon: NodeIcon,
    val schema: NodeSchema<C>,
    val outputPort: Port,
    @PublishedApi internal val output: DataOut<O>?,
    val hasDynamicPorts: Boolean,
    val permissions: List<PermissionRequirement> = emptyList(),
    val capabilities: List<DeviceCapability> = emptyList(),
) {
    /** Static metadata view for [io.github.m1n1m1.easymatic.domain.registry.NodeTypeRegistry]. */
    val nodeType: NodeTypeDefinition
        get() = NodeTypeDefinition(
            typeId = typeId,
            displayName = displayName,
            description = description,
            kind = NodeKind.VALUE,
            category = category,
            ports = listOf(outputPort),
            icon = icon,
            hasDynamicPorts = hasDynamicPorts,
            permissionRequirements = permissions,
            capabilities = capabilities,
        )

    /** Static config-form view for [io.github.m1n1m1.easymatic.domain.registry.ConfigSchemaRegistry]. */
    val configSchema: NodeConfigSchema?
        get() = schema.fields.takeIf { it.isNotEmpty() }?.let { NodeConfigSchema(typeId, it) }

    /** Wraps a successful read in the [Item] this node's output port carries. */
    internal fun encode(value: O): Item =
        requireNotNull(output) { "Value $typeId is adaptive and emits raw items" }.encode(value)
}

/**
 * The single declaration of a transform node, living in the transform's own file.
 *
 * A transform is the graph's pure *function* side: like a
 * [ValueNodeDefinition] it declares no EXECUTION ports and is never pulsed, but
 * unlike one it is not a leaf — it reads DATA inputs and derives its single DATA
 * output from them. Pulling a transform pulls whatever it depends on, so a chain
 * of transforms resolves in one go just before the node that consumes it.
 *
 * [output] is the declared port. For a transform whose output *type* is chosen in
 * its own config (`transform.convert`, `transform.json_read`) it is declared with
 * [io.github.m1n1m1.easymatic.domain.model.schema.ItemSchema.Wildcard] and retyped at
 * design time by [io.github.m1n1m1.easymatic.domain.registry.effectivePorts]; declaring
 * it statically anyway is what keeps the node offerable in the drag-into-empty-space
 * palette, which reads declared ports.
 */
@Suppress("LongParameterList") // A node definition is intentionally a flat declaration DSL.
class TransformNodeDefinition<C : Any, O : Any> @PublishedApi internal constructor(
    val typeId: NodeTypeId,
    val displayName: String,
    val description: String,
    val category: NodeCategory,
    val icon: NodeIcon,
    val schema: NodeSchema<C>,
    val outputPort: Port,
    @PublishedApi internal val output: DataOut<O>?,
    val extraPorts: List<Port>,
    val hasDynamicPorts: Boolean,
) {
    /** Static metadata view for [io.github.m1n1m1.easymatic.domain.registry.NodeTypeRegistry]. */
    val nodeType: NodeTypeDefinition
        get() = NodeTypeDefinition(
            typeId = typeId,
            displayName = displayName,
            description = description,
            kind = NodeKind.TRANSFORM,
            category = category,
            ports = schema.wiredPorts + extraPorts + outputPort,
            icon = icon,
            hasDynamicPorts = hasDynamicPorts,
        )

    /** Static config-form view for [io.github.m1n1m1.easymatic.domain.registry.ConfigSchemaRegistry]. */
    val configSchema: NodeConfigSchema?
        get() = schema.fields.takeIf { it.isNotEmpty() }?.let { NodeConfigSchema(typeId, it) }

    /** Wraps a typed result in the [Item] this transform's output port carries. */
    internal fun encode(value: O): Item =
        requireNotNull(output) { "Transform $typeId is adaptive and emits raw items" }.encode(value)
}

/**
 * Declares a transform whose output type is fixed, derived from the payload type
 * [O]. Register it in [io.github.m1n1m1.easymatic.domain.registry.TransformRegistry].
 */
@Suppress("LongParameterList") // A node definition is intentionally a flat declaration DSL.
inline fun <reified C : Any, O : Any> transformNode(
    typeId: String,
    displayName: String,
    description: String,
    category: NodeCategory,
    icon: NodeIcon,
    output: DataOut<O>,
    extraPorts: List<Port> = emptyList(),
): TransformNodeDefinition<C, O> = TransformNodeDefinition(
    typeId = NodeTypeId(typeId),
    displayName = displayName,
    description = description,
    category = category,
    icon = icon,
    schema = nodeSchema<C>(),
    outputPort = output.port,
    output = output,
    extraPorts = extraPorts,
    hasDynamicPorts = false,
)

/**
 * Declares a transform whose output type is **fixed** but whose input arrives as a
 * raw [Item] — the list transforms that count, join or search a list and answer
 * with a number, a text or a yes/no.
 *
 * The gap it fills: a DATA input derived from a `@Wired` config property can only
 * be a scalar ([io.github.m1n1m1.easymatic.domain.registry.NodeSchema] rejects
 * anything else), so a list has to arrive on a declared [extraPorts] port — and
 * reading one of those means being a [RawTransform], which until now implied
 * being *adaptive*. These are not: their output schema is known at declaration
 * time and they have no business in `effectivePorts`.
 *
 * [output] is therefore a real typed port rather than a wildcard placeholder, and
 * `hasDynamicPorts` stays false. The encoder is null because the node emits its
 * own [Item] — the same trade [RawTransform] already makes.
 */
@Suppress("LongParameterList") // A node definition is intentionally a flat declaration DSL.
inline fun <reified C : Any> rawTransformNode(
    typeId: String,
    displayName: String,
    description: String,
    category: NodeCategory,
    icon: NodeIcon,
    output: Port,
    extraPorts: List<Port> = emptyList(),
): TransformNodeDefinition<C, Unit> = TransformNodeDefinition(
    typeId = NodeTypeId(typeId),
    displayName = displayName,
    description = description,
    category = category,
    icon = icon,
    schema = nodeSchema<C>(),
    outputPort = output,
    output = null,
    extraPorts = extraPorts,
    hasDynamicPorts = false,
)

/**
 * Declares a transform whose output *schema* comes from its own config, and is
 * therefore resolved at design time by
 * [io.github.m1n1m1.easymatic.domain.registry.effectivePorts]. [output] is the
 * declared placeholder port; it must carry
 * [io.github.m1n1m1.easymatic.domain.model.schema.ItemSchema.Wildcard].
 */
@Suppress("LongParameterList") // A node definition is intentionally a flat declaration DSL.
inline fun <reified C : Any> adaptiveTransformNode(
    typeId: String,
    displayName: String,
    description: String,
    category: NodeCategory,
    icon: NodeIcon,
    output: Port,
    extraPorts: List<Port> = emptyList(),
): TransformNodeDefinition<C, Unit> = TransformNodeDefinition(
    typeId = NodeTypeId(typeId),
    displayName = displayName,
    description = description,
    category = category,
    icon = icon,
    schema = nodeSchema<C>(),
    outputPort = output,
    output = null,
    extraPorts = extraPorts,
    hasDynamicPorts = true,
)

/**
 * Declares a value node: a pure reader that emits a typed item on [output].
 * Register it in [io.github.m1n1m1.easymatic.domain.registry.ValueRegistry].
 *
 * Unlike an action or a trigger, a value node takes no [Port] arguments — its one
 * port is [output], and declaring a DATA input (via a `@Wired` config property)
 * is a contract violation because a value node is a leaf.
 *
 * [permissions] is for the read that the *platform* gates — see
 * [ValueNodeDefinition] for why that does not break purity. A value that declares
 * one must still answer null when the grant is missing rather than throwing.
 */
@Suppress("LongParameterList") // A node definition is intentionally a flat declaration DSL.
inline fun <reified C : Any, O : Any> valueNode(
    typeId: String,
    displayName: String,
    description: String,
    category: NodeCategory,
    icon: NodeIcon,
    output: DataOut<O>,
    permissions: List<PermissionRequirement> = emptyList(),
    capabilities: List<DeviceCapability> = emptyList(),
): ValueNodeDefinition<C, O> = ValueNodeDefinition(
    typeId = NodeTypeId(typeId),
    displayName = displayName,
    description = description,
    category = category,
    icon = icon,
    schema = nodeSchema<C>(),
    outputPort = output.port,
    output = output,
    hasDynamicPorts = false,
    permissions = permissions,
    capabilities = capabilities,
)

/**
 * Declares a value node whose output *schema* comes from its own config, and is
 * therefore resolved at design time by
 * [io.github.m1n1m1.easymatic.domain.registry.effectivePorts]. [output] is the declared
 * placeholder; it must carry
 * [io.github.m1n1m1.easymatic.domain.model.schema.ItemSchema.Wildcard].
 *
 * The mirror of [adaptiveTransformNode], and there is exactly one of these:
 * `value.variable`, whose type is whatever its declaration says. Every other value
 * reads a device property whose type the node itself knows.
 */
@Suppress("LongParameterList") // A node definition is intentionally a flat declaration DSL.
inline fun <reified C : Any> adaptiveValueNode(
    typeId: String,
    displayName: String,
    description: String,
    category: NodeCategory,
    icon: NodeIcon,
    output: Port,
): ValueNodeDefinition<C, Unit> = ValueNodeDefinition(
    typeId = NodeTypeId(typeId),
    displayName = displayName,
    description = description,
    category = category,
    icon = icon,
    schema = nodeSchema<C>(),
    outputPort = output,
    output = null,
    hasDynamicPorts = true,
)

/**
 * Declares an action that produces a typed data item on [output].
 *
 * The config class [I] supplies the config form, the DATA input ports and the
 * decoder; see [io.github.m1n1m1.easymatic.domain.model.config.Label] and friends.
 *
 * [permissions] are the runtime grants the action cannot work without, declared
 * here for the reason [triggerNode]'s are: so the config form can say so. Until
 * this existed only a trigger could declare one, and `action.call` simply returned
 * false without `CALL_PHONE` with nothing anywhere explaining it.
 */
@Suppress("LongParameterList") // A node definition is intentionally a flat declaration DSL.
inline fun <reified I : Any, O : Any> actionNode(
    typeId: String,
    displayName: String,
    description: String,
    category: NodeCategory,
    icon: NodeIcon,
    output: DataOut<O>,
    execOutputs: ExecOutputs = ExecOutputs.SINGLE,
    permissions: List<PermissionRequirement> = emptyList(),
    capabilities: List<DeviceCapability> = emptyList(),
    platformWarnings: List<PlatformWarning> = emptyList(),
): ActionNodeDefinition<I, O> = ActionNodeDefinition(
    typeId = NodeTypeId(typeId),
    displayName = displayName,
    description = description,
    category = category,
    icon = icon,
    schema = nodeSchema<I>(),
    output = output,
    execOutputs = execOutputs,
    extraPorts = emptyList(),
    hasDynamicPorts = false,
    permissions = permissions,
    capabilities = capabilities,
    platformWarnings = platformWarnings,
)

/**
 * Declares an action that performs a side effect and produces no data item.
 *
 * [extraPorts] covers the case where an effect still has to *read* something the
 * config class cannot describe — `action.list_add` takes a wildcard so it can
 * append an item with its type intact. That does not make the node adaptive:
 * nothing about these ports depends on the graph, so `hasDynamicPorts` stays false
 * and no schema resolution ever walks an edge looking for them.
 *
 * [hasDynamicPorts] is here rather than in [adaptiveNode] because the one effect
 * that needs it is not a [RawAction] or a [LoopAction], which is all that builder
 * is for: `action.set_variable` keeps its ordinary typed config and only wants its
 * `value` port retyped to whatever the variable it writes was declared as. Same
 * shape [loopNode] already offers, and for the same reason.
 */
@Suppress("LongParameterList")
inline fun <reified I : Any> effectNode(
    typeId: String,
    displayName: String,
    description: String,
    category: NodeCategory,
    icon: NodeIcon,
    execOutputs: ExecOutputs = ExecOutputs.SINGLE,
    extraPorts: List<Port> = emptyList(),
    hasDynamicPorts: Boolean = false,
    permissions: List<PermissionRequirement> = emptyList(),
    capabilities: List<DeviceCapability> = emptyList(),
): ActionNodeDefinition<I, Unit> = ActionNodeDefinition(
    typeId = NodeTypeId(typeId),
    displayName = displayName,
    description = description,
    category = category,
    icon = icon,
    schema = nodeSchema<I>(),
    output = null,
    execOutputs = execOutputs,
    extraPorts = extraPorts,
    hasDynamicPorts = hasDynamicPorts,
    permissions = permissions,
    capabilities = capabilities,
)

/**
 * Declares an *adaptive* action whose data ports are resolved at design time
 * from the graph ([io.github.m1n1m1.easymatic.domain.registry.effectivePorts]).
 * Reserved for `action.break`, `action.if` and `action.for_each`; see [RawAction]
 * and [LoopAction].
 *
 * [extraPorts] are the ports the config class cannot supply — a wildcard or list
 * input to read, and (for a loop) the per-iteration DATA outputs. They are
 * declared statically even when `effectivePorts` will retype them, because the
 * drag-into-empty-space palette reads declared ports.
 *
 * [permissions] are declared here for the same reason [actionNode] takes them.
 * Being adaptive says something about a node's *ports*, nothing about what it
 * needs to run: `action.dialog_input` retypes its answer port from its own
 * config and still cannot show anything without permission to draw over other
 * apps.
 */
@Suppress("LongParameterList")
inline fun <reified I : Any> adaptiveNode(
    typeId: String,
    displayName: String,
    description: String,
    category: NodeCategory,
    icon: NodeIcon,
    extraPorts: List<Port>,
    execOutputs: ExecOutputs = ExecOutputs.SINGLE,
    permissions: List<PermissionRequirement> = emptyList(),
    capabilities: List<DeviceCapability> = emptyList(),
): ActionNodeDefinition<I, Unit> = ActionNodeDefinition(
    typeId = NodeTypeId(typeId),
    displayName = displayName,
    description = description,
    category = category,
    icon = icon,
    schema = nodeSchema<I>(),
    output = null,
    execOutputs = execOutputs,
    extraPorts = extraPorts,
    hasDynamicPorts = true,
    permissions = permissions,
    capabilities = capabilities,
)

/**
 * Declares a [LoopAction]: `body` / `completed` exec outputs and the DATA outputs
 * each iteration carries.
 *
 * Separate from [adaptiveNode] because a loop whose ports are entirely static
 * (`action.repeat` emits an `index` and nothing else) has no business claiming
 * `hasDynamicPorts` — that flag makes every schema resolution walk the graph
 * looking for an answer this node does not have.
 */
@Suppress("LongParameterList")
inline fun <reified I : Any> loopNode(
    typeId: String,
    displayName: String,
    description: String,
    category: NodeCategory,
    icon: NodeIcon,
    extraPorts: List<Port>,
    hasDynamicPorts: Boolean = false,
): ActionNodeDefinition<I, Unit> = ActionNodeDefinition(
    typeId = NodeTypeId(typeId),
    displayName = displayName,
    description = description,
    category = category,
    icon = icon,
    schema = nodeSchema<I>(),
    output = null,
    execOutputs = ExecOutputs.LOOP,
    extraPorts = extraPorts,
    hasDynamicPorts = hasDynamicPorts,
)

/**
 * Declares a trigger that emits a typed data item on [output] when it fires.
 *
 * [permissions] are the runtime grants the trigger cannot fire without. They
 * are declared here, next to everything else about the node, so the config form
 * can warn about a missing one instead of the trigger simply never firing.
 *
 * [extraOutputs] are further ports projected out of the same event with
 * [io.github.m1n1m1.easymatic.domain.model.derivedOut] — the sparing exception to "a
 * trigger emits one struct", for a field that exists only to be wired into a
 * sibling node.
 */
@Suppress("LongParameterList")
inline fun <reified C : Any, O : Any> triggerNode(
    typeId: String,
    displayName: String,
    description: String,
    category: NodeCategory,
    icon: NodeIcon,
    output: DataOut<O>,
    extraOutputs: List<DataOut<O>> = emptyList(),
    permissions: List<PermissionRequirement> = emptyList(),
    capabilities: List<DeviceCapability> = emptyList(),
): TriggerNodeDefinition<C, O> = TriggerNodeDefinition(
    typeId = NodeTypeId(typeId),
    displayName = displayName,
    description = description,
    category = category,
    icon = icon,
    schema = nodeSchema<C>(),
    output = output,
    extraOutputs = extraOutputs,
    permissions = permissions,
    capabilities = capabilities,
)

/**
 * Declares a trigger that only pulses execution, carrying no data.
 *
 * [permissions] arrived with `trigger.calendar_changed`, which is the first pulse trigger
 * that needs a grant to hear anything at all — every earlier one is a broadcast the
 * platform hands over freely. Carrying no data says nothing about what a trigger needs in
 * order to fire, which is [adaptiveNode]'s argument for the same parameter.
 */
@Suppress("LongParameterList") // A node definition is intentionally a flat declaration DSL.
inline fun <reified C : Any> pulseTriggerNode(
    typeId: String,
    displayName: String,
    description: String,
    category: NodeCategory,
    icon: NodeIcon,
    permissions: List<PermissionRequirement> = emptyList(),
    capabilities: List<DeviceCapability> = emptyList(),
): TriggerNodeDefinition<C, Unit> = TriggerNodeDefinition(
    typeId = NodeTypeId(typeId),
    displayName = displayName,
    description = description,
    category = category,
    icon = icon,
    schema = nodeSchema<C>(),
    output = null,
    permissions = permissions,
    capabilities = capabilities,
)

/**
 * Declares a trigger whose DATA outputs are named in its own config, and are
 * therefore resolved at design time by
 * [io.github.m1n1m1.easymatic.domain.registry.effectivePorts].
 *
 * The trigger-side mirror of [adaptiveValueNode] and [adaptiveTransformNode], with
 * one difference that is worth stating because it looks like an omission: those two
 * declare a **wildcard placeholder** port and have it retyped, where this declares
 * *no data port at all* and has ports **added**. The reason is that their output
 * count is known and only its type is not, whereas here neither is — a trigger
 * carrying nothing is as ordinary as one carrying three values.
 *
 * That is also what keeps `NodeDeclarationContractTest`'s rule that a trigger's data
 * outputs are exactly its event plus the projections of it *true* rather than
 * weakened: with `output = null` and no `extraOutputs`, both sides of that assertion
 * are empty. A statically declared port here would be a socket nothing ever fills,
 * which is precisely what that rule exists to catch.
 *
 * There is exactly one of these — `trigger.api`, whose values are decided by the
 * app calling it rather than by anything in the graph.
 */
@Suppress("LongParameterList") // A node definition is intentionally a flat declaration DSL.
inline fun <reified C : Any> adaptiveTriggerNode(
    typeId: String,
    displayName: String,
    description: String,
    category: NodeCategory,
    icon: NodeIcon,
    permissions: List<PermissionRequirement> = emptyList(),
    capabilities: List<DeviceCapability> = emptyList(),
): TriggerNodeDefinition<C, Unit> = TriggerNodeDefinition(
    typeId = NodeTypeId(typeId),
    displayName = displayName,
    description = description,
    category = category,
    icon = icon,
    schema = nodeSchema<C>(),
    output = null,
    permissions = permissions,
    capabilities = capabilities,
    hasDynamicPorts = true,
)
