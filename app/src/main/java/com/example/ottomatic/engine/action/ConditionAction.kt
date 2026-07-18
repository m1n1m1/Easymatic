package com.example.ottomatic.engine.action

import com.example.ottomatic.engine.Action
import com.example.ottomatic.engine.ActionInput
import com.example.ottomatic.engine.ActionResult
import com.example.ottomatic.engine.ExecutionContext

/**
 * Action for `action.condition`. Evaluates a comparison against a field of the
 * runtime data context and routes execution to port `true` or `false`.
 *
 * The field is read from the flattened data context built by
 * [com.example.ottomatic.engine.WorkflowExecutor] from all data items produced
 * upstream in the current execution chain.
 */
class ConditionAction : Action {

    override val typeId: String = TYPE_ID

    override suspend fun execute(input: ActionInput, context: ExecutionContext): ActionResult {
        val field = input.config.str("field", default = "")
        val operator = input.config.str("operator", default = "equals")
        val compareValue = input.config.raw("value").orEmpty()
        val actualValue = input.dataContext[field].orEmpty()
        val matched = evaluate(operator, actualValue, compareValue)
        return ActionResult(execOut = if (matched) listOf("true") else listOf("false"))
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
