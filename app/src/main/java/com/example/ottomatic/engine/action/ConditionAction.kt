package com.example.ottomatic.engine.action

import com.example.ottomatic.engine.Action
import com.example.ottomatic.engine.ActionInput
import com.example.ottomatic.engine.ActionResult
import com.example.ottomatic.engine.ExecutionContext

/**
 * Action for `action.condition`. Evaluates a comparison and routes the
 * payload to port 0 (true) or port 1 (false).
 */
class ConditionAction : Action {

    override val typeId: String = TYPE_ID

    override suspend fun execute(input: ActionInput, context: ExecutionContext): ActionResult {
        val field = input.node.config["field"] ?: return falseResult(input)
        val operator = input.node.config["operator"] ?: "equals"
        val compareValue = input.node.config["value"].orEmpty()
        val actualValue = input.payload.values[field].orEmpty()
        val result = evaluate(operator, actualValue, compareValue)
        return if (result) ActionResult(mapOf(0 to input.payload)) else ActionResult(mapOf(1 to input.payload))
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

    private fun falseResult(input: ActionInput): ActionResult = ActionResult(mapOf(1 to input.payload))

    companion object {
        const val TYPE_ID = "action.condition"
    }
}
