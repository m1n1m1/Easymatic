package com.example.ottomatic.engine

import com.example.ottomatic.core.model.PortName
import com.example.ottomatic.domain.model.ConditionLogic
import com.example.ottomatic.domain.model.WorkflowNode
import com.example.ottomatic.domain.model.schema.Item
import com.example.ottomatic.domain.registry.ConditionRegistry

/**
 * Whether this node's attached conditions allow it to run.
 *
 * Shared by both gating points — [WorkflowExecutor] for actions and
 * [WorkflowRunner] for triggers — so "the conditions passed" means exactly one
 * thing in this codebase. [data] is whatever DATA items the node already has in
 * hand (its collected inputs, or a trigger's own emitted items), which is what
 * lets an attached condition inspect the flow without owning any ports.
 *
 * Two deliberate asymmetries: a condition that *throws* fails closed, because a
 * gate that errors should not silently open; a condition whose [typeId] is
 * *unknown* passes, because a workflow referencing a node type this build no
 * longer has should degrade to "ungated" rather than becoming permanently dead.
 */
internal suspend fun WorkflowNode.conditionsPass(
    data: Map<PortName, Item>,
    context: ExecutionContext,
): Boolean {
    if (conditions.isEmpty()) return true
    val results = conditions.map { attached ->
        val condition = ConditionRegistry.byId(attached.typeId)
        if (condition == null) {
            context.log("Unknown condition ${attached.typeId.value} on '$name': ignoring")
            return@map true
        }
        val passed = runCatching { condition.evaluateRaw(attached.config, this, data, context) }
            .getOrElse { cause ->
                context.log("Condition ${attached.typeId.value} failed: ${cause.message}")
                false
            }
        passed != attached.negated
    }
    return when (conditionLogic) {
        ConditionLogic.AND -> results.all { it }
        ConditionLogic.OR -> results.any { it }
    }
}
