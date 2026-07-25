package com.example.ottomatic.engine.condition

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
import com.example.ottomatic.engine.ConditionNode
import com.example.ottomatic.engine.ExecutionContext
import com.example.ottomatic.engine.NodeInput
import com.example.ottomatic.engine.conditionNode
import kotlinx.serialization.Serializable

/**
 * Config for `condition.compare`.
 *
 * The static form derived from this class is narrowed at design time by
 * [com.example.ottomatic.domain.registry.effectiveConfigSchema] (placed form) and
 * [com.example.ottomatic.domain.registry.effectiveConditionSchema] (attached
 * form): [field] becomes a dropdown of the inspected struct's fields, [operator]
 * is filtered to those valid for the inspected type, and [source] / [value] are
 * retyped to match.
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
 * `condition.compare` — the general typed comparison, and the node that carries
 * the graph's full expressive power into the condition family.
 *
 * Placed on the canvas it is the adaptive if-node it has always been: its
 * `source`/`value` port schemas and its config form are resolved from whatever is
 * wired into it, and it routes execution to `true` or `false`. Attached to
 * another node it compares against that host's own data inputs instead, so the
 * same comparison is available without drawing a single edge.
 */
class CompareCondition : ConditionNode<ConditionConfig> {

    override val definition = conditionNode<ConditionConfig>(
        typeId = CONDITION_TYPE_ID.value,
        displayName = "Compare",
        description = "Passes when a typed comparison of the connected data input holds",
        category = NodeCategory.CONDITION_DATA,
        icon = NodeIcon.SPLIT,
        wildcardInputs = listOf(
            wildcardDataIn(CONDITION_SOURCE_IN.value, label = "Source"),
            wildcardDataIn(CONDITION_VALUE_IN.value, label = "Compare against"),
        ),
        hasDynamicPorts = true,
    )

    override suspend fun evaluate(
        config: ConditionConfig,
        input: NodeInput,
        context: ExecutionContext,
    ): Boolean {
        // A wired port wins over the form literal on both sides. When attached,
        // `source` may instead name one of the host node's own input ports.
        val actual = sourceItem(config, input)?.let { inspect(config, it) } ?: config.source
        val expected = input.text(CONDITION_VALUE_IN) ?: config.value
        return config.operator.matches(actual, expected)
    }

    /**
     * The item to inspect: the node's own `source` port when one is wired (the
     * placed form), otherwise the host input port named by [ConditionConfig.source]
     * (the attached form, where this condition has no ports of its own).
     */
    private fun sourceItem(config: ConditionConfig, input: NodeInput): Item? =
        input.item(CONDITION_SOURCE_IN)
            ?: config.source.takeIf { it.isNotBlank() }?.let { input.item(PortName(it)) }

    /** The comparable text of [item]: a selected struct field in auto mode, else the whole value. */
    private fun inspect(config: ConditionConfig, item: Item): String {
        if (config.type == ComparisonType.AUTO && item.schema is ItemSchema.Object) {
            return item.flat[config.field].orEmpty()
        }
        return item.value?.toString().orEmpty()
    }
}
