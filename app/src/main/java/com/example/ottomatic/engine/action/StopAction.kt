package com.example.ottomatic.engine.action

import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.NodeIcon
import com.example.ottomatic.domain.model.config.Label
import com.example.ottomatic.domain.model.config.Wired
import com.example.ottomatic.engine.Action
import com.example.ottomatic.engine.ExecutionContext
import com.example.ottomatic.engine.NodeOutput
import com.example.ottomatic.engine.effectNode
import kotlinx.serialization.Serializable

/** Config for `action.stop`. */
@Serializable
data class StopConfig(
    @Label("Reason (optional, logged before halting)") @Wired val reason: String = "",
)

/**
 * Action for `action.stop`. Halts the current execution chain: the
 * [com.example.ottomatic.engine.WorkflowExecutor] stops following `execOut`
 * ports after this node runs. Optionally logs a reason (wired from upstream
 * data or a static literal) before halting. Pairs with
 * `trigger.macro_finished`.
 *
 * The halt is scoped to the current trigger's traversal — sibling chains
 * triggered by independent events are unaffected.
 *
 * **A fork is a second traversal**, and that is the one place this does not read
 * as it sounds. Reaching a Stop on the `Carry on now` branch of an
 * `action.wait_until` cancels a wait that has not fired yet, which is the case
 * worth having: a macro visibly still counting down is stopped along with
 * everything else. Once that branch has *started*, though, it is a walk of its
 * own with no caller left to unwind, so a Stop upstream can no longer reach it.
 * "Everything after this node, on this path" is the honest reading.
 */
class StopAction : Action<StopConfig, Unit> {

    override val definition = effectNode<StopConfig>(
        typeId = "action.stop",
        displayName = "Stop Macro",
        description = "Halts the current execution chain (stops following connected actions)",
        category = NodeCategory.FLOW_CONTROL,
        icon = NodeIcon.BOLT,
    )

    override suspend fun execute(input: StopConfig, context: ExecutionContext): NodeOutput<Unit> {
        if (input.reason.isNotBlank()) context.log("Stop: ${input.reason}")
        return NodeOutput(Unit, halt = true)
    }
}
