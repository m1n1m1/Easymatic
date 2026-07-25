package com.example.ottomatic.engine

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
class TriggerNodeDefinition<C : Any, O : Any> @PublishedApi internal constructor(
    val typeId: NodeTypeId,
    val displayName: String,
    val description: String,
    val category: NodeCategory,
    val icon: NodeIcon,
    val schema: NodeSchema<C>,
    val output: DataOut<O>?,
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
        )

    /** Static config-form view for [com.example.ottomatic.domain.registry.ConfigSchemaRegistry]. */
    val configSchema: NodeConfigSchema?
        get() = schema.fields.takeIf { it.isNotEmpty() }?.let { NodeConfigSchema(typeId, it) }

    /** Encodes a typed trigger event onto this node's declared data output port. */
    internal fun encode(value: O): Map<PortName, Item> =
        output?.let { mapOf(it.name to it.encode(value)) }.orEmpty()
}

/**
 * The single declaration of a condition node, living in the condition's own file.
 *
 * A condition has two placements, both served from here:
 *
 *  - **placed** on the canvas, where [nodeType] presents it as a branching node
 *    (exec in, `true`/`false` exec out, plus its wired/wildcard DATA inputs) and
 *    [com.example.ottomatic.engine.ConditionAsAction] runs it through the normal
 *    executor path;
 *  - **attached** to another node as an
 *    [com.example.ottomatic.domain.model.AttachedCondition], where it gates that
 *    node and reads the host's already-collected data inputs.
 *
 * Neither placement re-declares anything: the config form, the DATA input ports
 * and the decoder all come from the config class [C] via [schema], exactly as
 * for [ActionNodeDefinition].
 */
@Suppress("LongParameterList") // A node definition is intentionally a flat declaration DSL.
class ConditionNodeDefinition<C : Any> @PublishedApi internal constructor(
    val typeId: NodeTypeId,
    val displayName: String,
    val description: String,
    val category: NodeCategory,
    val icon: NodeIcon,
    val schema: NodeSchema<C>,
    val wildcardInputs: List<Port>,
    val hasDynamicPorts: Boolean,
) {
    /** Canvas-placement metadata view for [com.example.ottomatic.domain.registry.NodeTypeRegistry]. */
    val nodeType: NodeTypeDefinition
        get() = NodeTypeDefinition(
            typeId = typeId,
            displayName = displayName,
            description = description,
            kind = NodeKind.CONDITION,
            category = category,
            ports = listOf(execIn()) + ExecOutputs.BRANCH.ports + schema.wiredPorts + wildcardInputs,
            icon = icon,
            hasDynamicPorts = hasDynamicPorts,
        )

    /** Static config-form view for [com.example.ottomatic.domain.registry.ConfigSchemaRegistry]. */
    val configSchema: NodeConfigSchema?
        get() = schema.fields.takeIf { it.isNotEmpty() }?.let { NodeConfigSchema(typeId, it) }
}

/**
 * Declares a condition: a node that answers true/false rather than performing
 * work. Register it in [com.example.ottomatic.domain.registry.ConditionRegistry].
 *
 * Pass [wildcardInputs] (and [hasDynamicPorts]) only when the condition's DATA
 * input schemas are resolved from the graph at design time, as `condition.compare`
 * does; a self-contained condition declares neither.
 */
@Suppress("LongParameterList") // A node definition is intentionally a flat declaration DSL.
inline fun <reified C : Any> conditionNode(
    typeId: String,
    displayName: String,
    description: String,
    category: NodeCategory,
    icon: NodeIcon,
    wildcardInputs: List<Port> = emptyList(),
    hasDynamicPorts: Boolean = false,
): ConditionNodeDefinition<C> = ConditionNodeDefinition(
    typeId = NodeTypeId(typeId),
    displayName = displayName,
    description = description,
    category = category,
    icon = icon,
    schema = nodeSchema<C>(),
    wildcardInputs = wildcardInputs,
    hasDynamicPorts = hasDynamicPorts,
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
 * Reserved for `action.break` and `action.condition`; see [RawAction].
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

/** Declares a trigger that emits a typed data item on [output] when it fires. */
@Suppress("LongParameterList")
inline fun <reified C : Any, O : Any> triggerNode(
    typeId: String,
    displayName: String,
    description: String,
    category: NodeCategory,
    icon: NodeIcon,
    output: DataOut<O>,
): TriggerNodeDefinition<C, O> = TriggerNodeDefinition(
    typeId = NodeTypeId(typeId),
    displayName = displayName,
    description = description,
    category = category,
    icon = icon,
    schema = nodeSchema<C>(),
    output = output,
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
