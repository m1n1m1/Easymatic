package com.example.ottomatic.engine.action

import com.example.ottomatic.domain.model.schema.Item
import com.example.ottomatic.domain.model.schema.ItemSchema
import com.example.ottomatic.domain.registry.CONDITION_SOURCE_IN
import com.example.ottomatic.domain.registry.CONDITION_TYPE_AUTO
import com.example.ottomatic.domain.registry.CONDITION_TYPE_CONFIG_KEY
import com.example.ottomatic.engine.Action
import com.example.ottomatic.engine.ActionInput
import com.example.ottomatic.engine.ActionResult
import com.example.ottomatic.engine.ExecutionContext

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
 */
class ConditionAction : Action {

    override val typeId: String = TYPE_ID

    override suspend fun execute(input: ActionInput, context: ExecutionContext): ActionResult {
        val typeConfig = input.config.str(CONDITION_TYPE_CONFIG_KEY, default = CONDITION_TYPE_AUTO)
        val operator = input.config.str("operator", default = "equals")
        val item = input.dataIn[CONDITION_SOURCE_IN]
        val compareValue = input.string("value")
        // When `source` is wired, use the typed Item; otherwise fall back to
        // the `source` config literal (the form field value).
        val actualValue = if (item != null) {
            actualFieldValue(typeConfig, item, input)
        } else {
            input.config.raw(CONDITION_SOURCE_IN).orEmpty()
        }
        val matched = evaluate(operator, actualValue, compareValue)
        return ActionResult(execOut = if (matched) listOf("true") else listOf("false"))
    }

    private fun actualFieldValue(typeConfig: String, item: Item, input: ActionInput): String {
        if (typeConfig == CONDITION_TYPE_AUTO && item.schema is ItemSchema.Object) {
            val field = input.config.str("field", default = "")
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

    companion object {
        const val TYPE_ID = "action.condition"
    }
}
