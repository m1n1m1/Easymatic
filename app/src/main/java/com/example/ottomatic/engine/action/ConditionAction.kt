package com.example.ottomatic.engine.action

import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.execOut
import com.example.ottomatic.domain.model.schema.Item
import com.example.ottomatic.domain.model.wildcardDataIn
import com.example.ottomatic.domain.registry.CONDITION_SOURCE_IN
import com.example.ottomatic.domain.registry.CONDITION_TYPE_AUTO
import com.example.ottomatic.domain.registry.CONDITION_TYPE_CONFIG_KEY
import com.example.ottomatic.domain.registry.CONDITION_TYPE_ID
import com.example.ottomatic.engine.Action
import com.example.ottomatic.engine.ExecutionContext
import com.example.ottomatic.engine.ExecutionRoute
import com.example.ottomatic.engine.NodeOutput
import com.example.ottomatic.engine.actionNode
import com.example.ottomatic.domain.model.schema.ItemSchema

data class ConditionInput(
    val type: String,
    val operator: String,
    val source: Item?,
    val sourceFallback: String,
    val compareValue: String,
    val field: String,
)

/**
 * Action for `action.condition`. Reads a typed [Item] on its [CONDITION_SOURCE_IN]
 * DATA input and compares it (or a selected field of it, when the source is a
 * struct and `type` is `"auto"`) against the `value` DATA input (or its static
 * config literal fallback) using the configured `operator`. Routes execution
 * to port `true` or `false`.
 *
 * The comparison type is determined by the `type` config field
 * (see [com.example.ottomatic.domain.registry.effectiveConfigSchema]):
 *  - `"auto"`: infer from the connected item's schema. A struct source exposes
 *    a `field` picker; a primitive source is compared directly.
 *  - a specific primitive (`"int"`, `"string"`, ...): compare the whole item
 *    value as that primitive type. The `source` input port is locked to that
 *    schema at design time so the validator enforces the match.
 *
 * Both `source` and `value` are DATA input ports; when either is unwired, the
 * static config form value for the same key is used as a fallback.
 *
 * The config form is fully dynamic ([com.example.ottomatic.domain.registry.effectiveConfigSchema]),
 * so this definition declares no static config fields.
 */
class ConditionAction : Action<ConditionInput, Boolean> {

    override val definition = actionNode<ConditionInput, Boolean>(
        typeId = CONDITION_TYPE_ID,
        displayName = "If / Condition",
        description = "Routes execution based on a typed comparison of a field of the connected data input",
        category = NodeCategory.FLOW_CONTROL,
        iconKey = "split",
        dataInputs = listOf(
            wildcardDataIn(CONDITION_SOURCE_IN),
            wildcardDataIn("value"),
        ),
        execOutputs = listOf(execOut("true"), execOut("false")),
        hasDynamicPorts = true,
        encodeRoute = { route -> listOf(if (route == ExecutionRoute.True) "true" else "false") },
        decode = { input ->
            ConditionInput(
                type = input.configString(CONDITION_TYPE_CONFIG_KEY, CONDITION_TYPE_AUTO),
                operator = input.configString("operator", "equals"),
                source = input.item(CONDITION_SOURCE_IN),
                sourceFallback = input.configString(CONDITION_SOURCE_IN),
                compareValue = input.text("value"),
                field = input.configString("field"),
            )
        },
        encodeData = { emptyMap() },
    )

    override suspend fun execute(input: ConditionInput, context: ExecutionContext): NodeOutput<Boolean> {
        // When `source` is wired, use the typed Item; otherwise fall back to
        // the `source` config literal (the form field value).
        val actualValue = if (input.source != null) {
            actualFieldValue(input.type, input.source, input.field)
        } else {
            input.sourceFallback
        }
        val matched = evaluate(input.operator, actualValue, input.compareValue)
        return NodeOutput(matched, if (matched) ExecutionRoute.True else ExecutionRoute.False)
    }

    private fun actualFieldValue(typeConfig: String, item: Item, field: String): String {
        if (typeConfig == CONDITION_TYPE_AUTO && item.schema is ItemSchema.Object) {
            return item.flat[field] ?: ""
        }
        return item.value?.toString() ?: ""
    }

    private fun evaluate(operator: String, actual: String, expected: String): Boolean {
        val actualNum = actual.toDoubleOrNull()
        val expectedNum = expected.toDoubleOrNull()
        return when (operator) {
            "equals" -> actual == expected
            "notEquals" -> actual != expected
            "contains" -> actual.contains(expected)
            "matchesRegex" -> actual.matches(Regex(expected))
            else -> compareNumbers(operator, actualNum, expectedNum)
        }
    }

    private fun compareNumbers(operator: String, actual: Double?, expected: Double?): Boolean {
        if (actual == null || expected == null) return false
        return when (operator) {
            "greaterThan" -> actual > expected
            "lessThan" -> actual < expected
            "greaterThanOrEqual" -> actual >= expected
            "lessThanOrEqual" -> actual <= expected
            else -> false
        }
    }
}
