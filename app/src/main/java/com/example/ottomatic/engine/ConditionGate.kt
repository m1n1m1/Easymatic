package com.example.ottomatic.engine

import com.example.ottomatic.core.model.PortName
import com.example.ottomatic.domain.model.ConditionLogic
import com.example.ottomatic.domain.model.WorkflowNode
import com.example.ottomatic.domain.model.schema.Item
import com.example.ottomatic.domain.registry.IF_TYPE_ID
import com.example.ottomatic.domain.registry.ActionRegistry
import com.example.ottomatic.engine.action.CompareConfig
import com.example.ottomatic.engine.action.IfAction

/**
 * The schema used to decode an attached gate's config.
 *
 * Taken from the registered [IfAction] itself rather than re-derived, so a gate and
 * a placed `action.if` are decoded by the very same [NodeSchema] — the same reason
 * they share [evaluateCompare].
 */
@Suppress("UNCHECKED_CAST")
private val compareSchema by lazy {
    (ActionRegistry.byId(IF_TYPE_ID) as IfAction).definition.schema
}

/**
 * Whether this node's attached gates allow it to run.
 *
 * Shared by both gating points — [WorkflowExecutor] for actions and
 * [WorkflowRunner] for triggers — so "the conditions passed" means exactly one
 * thing in this codebase. [data] is whatever DATA items the node already has in
 * hand (its collected inputs, or a trigger's own emitted items), which is what
 * lets a gate inspect the flow without owning any ports.
 *
 * Every gate is the graph's single comparison ([evaluateCompare]), so there is no
 * registry lookup and no unknown-type case: what varies between gates is the
 * source their config names. A gate whose source cannot be resolved — an
 * unregistered value node, a host port carrying nothing — compares against
 * nothing and so fails closed, as does one that throws. A gate that errors must
 * never silently open.
 */
internal suspend fun WorkflowNode.conditionsPass(
    data: Map<PortName, Item>,
    context: ExecutionContext,
): Boolean {
    if (conditions.isEmpty()) return true
    val results = conditions.map { attached ->
        val passed = runCatching {
            val config: CompareConfig = compareSchema.decode(attached.config, data)
            evaluateCompare(config, NodeInput(this, data), context)
        }.getOrElse { cause ->
            context.log("Condition on '$name' failed: ${cause.message}")
            false
        }
        passed != attached.negated
    }
    return when (conditionLogic) {
        ConditionLogic.AND -> results.all { it }
        ConditionLogic.OR -> results.any { it }
    }
}
