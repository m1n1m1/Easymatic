package com.example.ottomatic.engine

import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.NodeKind
import com.example.ottomatic.domain.model.NodeTypeDefinition
import com.example.ottomatic.domain.model.Port
import com.example.ottomatic.domain.model.execIn
import com.example.ottomatic.domain.model.execOut
import com.example.ottomatic.domain.model.schema.Item
import com.example.ottomatic.domain.registry.ConfigField
import com.example.ottomatic.domain.registry.NodeConfigSchema

/**
 * The single declaration of an action node: palette metadata, ports, config
 * form fields and the typed contract, all in one place inside the action's
 * own file. [nodeType] and [configSchema] are derived views consumed by the
 * domain registries ([com.example.ottomatic.domain.registry.NodeTypeRegistry],
 * [com.example.ottomatic.domain.registry.ConfigSchemaRegistry]).
 *
 * The port list of the derived [nodeType] is composed as
 * `execIn + execOutputs + dataInputs + dataOutputs`, matching the historical
 * layout. Actions with a single `out` exec port need only declare their data
 * ports; branching actions (e.g. `action.condition`) override [execOutputs],
 * and adaptive nodes set [hasDynamicPorts].
 */
@Suppress("LongParameterList") // A node definition is intentionally a flat declaration DSL.
class ActionNodeDefinition<I : Any, O : Any>(
    val typeId: String,
    val displayName: String,
    val description: String,
    val category: NodeCategory,
    val iconKey: String,
    val dataInputs: List<Port> = emptyList(),
    val dataOutputs: List<Port> = emptyList(),
    val execOutputs: List<Port> = listOf(execOut()),
    val hasDynamicPorts: Boolean = false,
    val configFields: List<ConfigField<*>> = emptyList(),
    val contract: ActionContract<I, O>,
) {
    /** Static metadata view for [com.example.ottomatic.domain.registry.NodeTypeRegistry]. */
    val nodeType: NodeTypeDefinition
        get() = NodeTypeDefinition(
            typeId = typeId,
            displayName = displayName,
            description = description,
            kind = NodeKind.ACTION,
            category = category,
            ports = listOf(execIn()) + execOutputs + dataInputs + dataOutputs,
            iconKey = iconKey,
            hasDynamicPorts = hasDynamicPorts,
        )

    /** Static config-form view for [com.example.ottomatic.domain.registry.ConfigSchemaRegistry]. */
    val configSchema: NodeConfigSchema?
        get() = configFields.takeIf { it.isNotEmpty() }?.let { NodeConfigSchema(typeId, it) }
}

/**
 * The single declaration of a trigger node: palette metadata, data output
 * ports, config form fields and the encoder that maps the trigger's typed
 * output [O] onto its declared DATA ports. Declared once inside the trigger's
 * own file; [nodeType] and [configSchema] are derived registry views.
 *
 * Every trigger has a single EXECUTION `out` port, so only DATA outputs are
 * declared here.
 */
@Suppress("LongParameterList") // A node definition is intentionally a flat declaration DSL.
class TriggerNodeDefinition<O : Any>(
    val typeId: String,
    val displayName: String,
    val description: String,
    val category: NodeCategory,
    val iconKey: String,
    val dataOutputs: List<Port> = emptyList(),
    val configFields: List<ConfigField<*>> = emptyList(),
    val encodeData: (O) -> Map<String, Item>,
) {
    /** Static metadata view for [com.example.ottomatic.domain.registry.NodeTypeRegistry]. */
    val nodeType: NodeTypeDefinition
        get() = NodeTypeDefinition(
            typeId = typeId,
            displayName = displayName,
            description = description,
            kind = NodeKind.TRIGGER,
            category = category,
            ports = listOf(execOut()) + dataOutputs,
            iconKey = iconKey,
        )

    /** Static config-form view for [com.example.ottomatic.domain.registry.ConfigSchemaRegistry]. */
    val configSchema: NodeConfigSchema?
        get() = configFields.takeIf { it.isNotEmpty() }?.let { NodeConfigSchema(typeId, it) }
}

/**
 * Builds an [ActionNodeDefinition] together with its [ActionContract] from a
 * single parameter set, so [typeId] and the encode/decode pair exist exactly
 * once. See [ActionNodeDefinition] for the port composition rules.
 */
@Suppress("LongParameterList")
fun <I : Any, O : Any> actionNode(
    typeId: String,
    displayName: String,
    description: String,
    category: NodeCategory,
    iconKey: String,
    dataInputs: List<Port> = emptyList(),
    dataOutputs: List<Port> = emptyList(),
    execOutputs: List<Port> = listOf(execOut()),
    hasDynamicPorts: Boolean = false,
    configFields: List<ConfigField<*>> = emptyList(),
    encodeRoute: (ExecutionRoute) -> List<String> = { listOf("out") },
    decode: (NodeInput) -> I,
    encodeData: (O) -> Map<String, Item>,
): ActionNodeDefinition<I, O> = ActionNodeDefinition(
    typeId = typeId,
    displayName = displayName,
    description = description,
    category = category,
    iconKey = iconKey,
    dataInputs = dataInputs,
    dataOutputs = dataOutputs,
    execOutputs = execOutputs,
    hasDynamicPorts = hasDynamicPorts,
    configFields = configFields,
    contract = ActionContract(typeId, decode, encodeData, encodeRoute),
)

/** Builds a [TriggerNodeDefinition]; see its doc for the port composition rules. */
@Suppress("LongParameterList") // Mirrors the definition's declaration DSL.
fun <O : Any> triggerNode(
    typeId: String,
    displayName: String,
    description: String,
    category: NodeCategory,
    iconKey: String,
    dataOutputs: List<Port> = emptyList(),
    configFields: List<ConfigField<*>> = emptyList(),
    encodeData: (O) -> Map<String, Item>,
): TriggerNodeDefinition<O> = TriggerNodeDefinition(
    typeId = typeId,
    displayName = displayName,
    description = description,
    category = category,
    iconKey = iconKey,
    dataOutputs = dataOutputs,
    configFields = configFields,
    encodeData = encodeData,
)
