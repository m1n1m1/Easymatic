package com.example.ottomatic.engine.action

import com.example.ottomatic.core.model.PortName
import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.NodeIcon
import com.example.ottomatic.domain.model.config.ComparisonOperator
import com.example.ottomatic.domain.model.config.ComparisonType
import com.example.ottomatic.domain.model.config.Label
import com.example.ottomatic.domain.model.schema.Item
import com.example.ottomatic.domain.model.schema.ItemSchema
import com.example.ottomatic.domain.model.wildcardDataIn
import com.example.ottomatic.domain.registry.CONDITION_SOURCE_IN
import com.example.ottomatic.domain.registry.CONDITION_TYPE_ID
import com.example.ottomatic.domain.registry.CONDITION_VALUE_IN
import com.example.ottomatic.engine.ExecOutputs
import com.example.ottomatic.engine.ExecutionContext
import com.example.ottomatic.engine.ExecutionRoute
import com.example.ottomatic.engine.NodeInput
import com.example.ottomatic.engine.NodeOutput
import com.example.ottomatic.engine.RawAction
import com.example.ottomatic.engine.adaptiveNode
import kotlinx.serialization.Serializable

/**
 * Config for `action.condition`.
 *
 * The static form derived from this class is narrowed at design time by
 * [com.example.ottomatic.domain.registry.effectiveConfigSchema]: [field] becomes
 * a dropdown of the connected struct's fields, [operator] is filtered to those
 * valid for the inspected type, and [source] / [value] are retyped to match.
 */
@Serializable
data class ConditionConfig(
    @Label("Type") val type: ComparisonType = ComparisonType.AUTO,
    @Label("Field") val field: String = "",
    @Label("Operator") val operator: ComparisonOperator = ComparisonOperator.EQUALS,
    @Label("Source") val source: String = "",
    @Label("Compare against") val value: String = "",
)

/**
 * Action for `action.condition`. Reads a typed [Item] on its `source` DATA
 * input and compares it — or a selected field of it, when the source is a struct
 * and the type is [ComparisonType.AUTO] — against the `value` input (or its
 * static literal fallback), routing execution to `true` or `false`.
 *
 * One of the two adaptive nodes: its `source`/`value` port schemas and its
 * config form are resolved from the graph, so it reads raw [Item]s
 * ([RawAction]) while still receiving its own settings as a typed
 * [ConditionConfig].
 */
class ConditionAction : RawAction<ConditionConfig> {

    override val definition = adaptiveNode<ConditionConfig>(
        typeId = CONDITION_TYPE_ID.value,
        displayName = "If / Condition",
        description = "Routes execution based on a typed comparison of a field of the connected data input",
        category = NodeCategory.FLOW_CONTROL,
        icon = NodeIcon.SPLIT,
        wildcardInputs = listOf(
            wildcardDataIn(CONDITION_SOURCE_IN.value, label = "Source"),
            wildcardDataIn(CONDITION_VALUE_IN.value, label = "Compare against"),
        ),
        execOutputs = ExecOutputs.BRANCH,
    )

    override suspend fun executeRaw(
        config: ConditionConfig,
        input: NodeInput,
        context: ExecutionContext,
    ): NodeOutput<Map<PortName, Item>> {
        // A wired port wins over the form literal on both sides.
        val actual = input.item(CONDITION_SOURCE_IN)?.let { inspect(config, it) } ?: config.source
        val expected = input.text(CONDITION_VALUE_IN) ?: config.value
        val matched = config.operator.matches(actual, expected)
        return NodeOutput(
            value = emptyMap(),
            route = if (matched) ExecutionRoute.TRUE else ExecutionRoute.FALSE,
        )
    }

    /** The comparable text of [item]: a selected struct field in auto mode, else the whole value. */
    private fun inspect(config: ConditionConfig, item: Item): String {
        if (config.type == ComparisonType.AUTO && item.schema is ItemSchema.Object) {
            return item.flat[config.field].orEmpty()
        }
        return item.value?.toString().orEmpty()
    }
}
