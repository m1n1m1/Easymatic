package com.example.ottomatic.engine

import com.example.ottomatic.core.permissions.PermissionRequirement
import com.example.ottomatic.domain.model.DataOut
import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.NodeIcon
import com.example.ottomatic.domain.model.NodeKind
import com.example.ottomatic.domain.model.NodeTypeDefinition
import com.example.ottomatic.core.model.NodeTypeId
import com.example.ottomatic.domain.model.Port
import com.example.ottomatic.core.model.PortName
import com.example.ottomatic.domain.model.execIn
import com.example.ottomatic.domain.model.execOut
import com.example.ottomatic.domain.model.schema.Item
import com.example.ottomatic.domain.registry.NodeConfigSchema
import com.example.ottomatic.domain.registry.NodeSchema
import com.example.ottomatic.domain.registry.nodeSchema

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
 * [com.example.ottomatic.domain.registry.NodeTypeRegistry] and
 * [com.example.ottomatic.domain.registry.ConfigSchemaRegistry].
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
    val wildcardInputs: List<Port>,
    val hasDynamicPorts: Boolean,
) {
    /** Static metadata view for [com.example.ottomatic.domain.registry.NodeTypeRegistry]. */
    val nodeType: NodeTypeDefinition
        get() = NodeTypeDefinition(
            typeId = typeId,
            displayName = displayName,
            description = description,
            kind = NodeKind.ACTION,
            category = category,
            ports = listOf(execIn()) + execOutputs.ports + schema.wiredPorts + wildcardInputs +
                listOfNotNull(output?.port),
            icon = icon,
            hasDynamicPorts = hasDynamicPorts,
        )

    /** Static config-form view for [com.example.ottomatic.domain.registry.ConfigSchemaRegistry]. */
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
    val permissions: List<PermissionRequirement> = emptyList(),
) {
    /** Static metadata view for [com.example.ottomatic.domain.registry.NodeTypeRegistry]. */
    val nodeType: NodeTypeDefinition
        get() = NodeTypeDefinition(
            typeId = typeId,
            displayName = displayName,
            description = description,
            kind = NodeKind.TRIGGER,
            category = category,
            ports = listOf(execOut()) + listOfNotNull(output?.port),
            icon = icon,
            permissionRequirements = permissions,
        )

    /** Static config-form view for [com.example.ottomatic.domain.registry.ConfigSchemaRegistry]. */
    val configSchema: NodeConfigSchema?
        get() = schema.fields.takeIf { it.isNotEmpty() }?.let { NodeConfigSchema(typeId, it) }

    /** Encodes a typed trigger event onto this node's declared data output port. */
    internal fun encode(value: O): Map<PortName, Item> =
        output?.let { mapOf(it.name to it.encode(value)) }.orEmpty()
}

/**
 * The single declaration of a value node, living in the reader's own file.
 *
 * A value node is the graph's pull side: a side-effect-free reader of something
 * that is true *right now*. It declares exactly one port — the typed DATA output
 * [output] — and no EXECUTION ports at all, because it is never pulsed. Instead
 * it is read on demand, immediately before whichever node consumes it (see
 * [com.example.ottomatic.engine.WorkflowExecutor]), or named directly by an
 * attached gate that has no edges of its own (see
 * [com.example.ottomatic.engine.ValueSource]).
 *
 * Purity is a contract, not a convention: `NodeDeclarationContractTest` asserts
 * that every value node declares no exec ports, no DATA inputs and no permission
 * requirements. Anything expensive, failable or side-effecting is an action.
 */
@Suppress("LongParameterList") // A node definition is intentionally a flat declaration DSL.
class ValueNodeDefinition<C : Any, O : Any> @PublishedApi internal constructor(
    val typeId: NodeTypeId,
    val displayName: String,
    val description: String,
    val category: NodeCategory,
    val icon: NodeIcon,
    val schema: NodeSchema<C>,
    val output: DataOut<O>,
) {
    /** Static metadata view for [com.example.ottomatic.domain.registry.NodeTypeRegistry]. */
    val nodeType: NodeTypeDefinition
        get() = NodeTypeDefinition(
            typeId = typeId,
            displayName = displayName,
            description = description,
            kind = NodeKind.VALUE,
            category = category,
            ports = listOf(output.port),
            icon = icon,
        )

    /** Static config-form view for [com.example.ottomatic.domain.registry.ConfigSchemaRegistry]. */
    val configSchema: NodeConfigSchema?
        get() = schema.fields.takeIf { it.isNotEmpty() }?.let { NodeConfigSchema(typeId, it) }

    /** Wraps a successful read in the [Item] this node's output port carries. */
    internal fun encode(value: O): Item = output.encode(value)
}

/**
 * Declares a value node: a pure reader that emits a typed item on [output].
 * Register it in [com.example.ottomatic.domain.registry.ValueRegistry].
 *
 * Unlike an action or a trigger, a value node takes no [Port] arguments — its one
 * port is [output], and declaring a DATA input (via a `@Wired` config property)
 * is a contract violation because a value node is a leaf.
 */
@Suppress("LongParameterList") // A node definition is intentionally a flat declaration DSL.
inline fun <reified C : Any, O : Any> valueNode(
    typeId: String,
    displayName: String,
    description: String,
    category: NodeCategory,
    icon: NodeIcon,
    output: DataOut<O>,
): ValueNodeDefinition<C, O> = ValueNodeDefinition(
    typeId = NodeTypeId(typeId),
    displayName = displayName,
    description = description,
    category = category,
    icon = icon,
    schema = nodeSchema<C>(),
    output = output,
)

/**
 * Declares an action that produces a typed data item on [output].
 *
 * The config class [I] supplies the config form, the DATA input ports and the
 * decoder; see [com.example.ottomatic.domain.model.config.Label] and friends.
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
): ActionNodeDefinition<I, O> = ActionNodeDefinition(
    typeId = NodeTypeId(typeId),
    displayName = displayName,
    description = description,
    category = category,
    icon = icon,
    schema = nodeSchema<I>(),
    output = output,
    execOutputs = execOutputs,
    wildcardInputs = emptyList(),
    hasDynamicPorts = false,
)

/** Declares an action that performs a side effect and produces no data item. */
@Suppress("LongParameterList")
inline fun <reified I : Any> effectNode(
    typeId: String,
    displayName: String,
    description: String,
    category: NodeCategory,
    icon: NodeIcon,
    execOutputs: ExecOutputs = ExecOutputs.SINGLE,
): ActionNodeDefinition<I, Unit> = ActionNodeDefinition(
    typeId = NodeTypeId(typeId),
    displayName = displayName,
    description = description,
    category = category,
    icon = icon,
    schema = nodeSchema<I>(),
    output = null,
    execOutputs = execOutputs,
    wildcardInputs = emptyList(),
    hasDynamicPorts = false,
)

/**
 * Declares an *adaptive* action whose data ports are resolved at design time
 * from the graph ([com.example.ottomatic.domain.registry.effectivePorts]).
 * Reserved for `action.break` and `action.if`; see [RawAction].
 */
@Suppress("LongParameterList")
inline fun <reified I : Any> adaptiveNode(
    typeId: String,
    displayName: String,
    description: String,
    category: NodeCategory,
    icon: NodeIcon,
    wildcardInputs: List<Port>,
    execOutputs: ExecOutputs = ExecOutputs.SINGLE,
): ActionNodeDefinition<I, Unit> = ActionNodeDefinition(
    typeId = NodeTypeId(typeId),
    displayName = displayName,
    description = description,
    category = category,
    icon = icon,
    schema = nodeSchema<I>(),
    output = null,
    execOutputs = execOutputs,
    wildcardInputs = wildcardInputs,
    hasDynamicPorts = true,
)

/**
 * Declares a trigger that emits a typed data item on [output] when it fires.
 *
 * [permissions] are the runtime grants the trigger cannot fire without. They
 * are declared here, next to everything else about the node, so the config form
 * can warn about a missing one instead of the trigger simply never firing.
 */
@Suppress("LongParameterList")
inline fun <reified C : Any, O : Any> triggerNode(
    typeId: String,
    displayName: String,
    description: String,
    category: NodeCategory,
    icon: NodeIcon,
    output: DataOut<O>,
    permissions: List<PermissionRequirement> = emptyList(),
): TriggerNodeDefinition<C, O> = TriggerNodeDefinition(
    typeId = NodeTypeId(typeId),
    displayName = displayName,
    description = description,
    category = category,
    icon = icon,
    schema = nodeSchema<C>(),
    output = output,
    permissions = permissions,
)

/** Declares a trigger that only pulses execution, carrying no data. */
inline fun <reified C : Any> pulseTriggerNode(
    typeId: String,
    displayName: String,
    description: String,
    category: NodeCategory,
    icon: NodeIcon,
): TriggerNodeDefinition<C, Unit> = TriggerNodeDefinition(
    typeId = NodeTypeId(typeId),
    displayName = displayName,
    description = description,
    category = category,
    icon = icon,
    schema = nodeSchema<C>(),
    output = null,
)
