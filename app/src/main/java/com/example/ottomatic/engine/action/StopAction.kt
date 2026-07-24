package com.example.ottomatic.engine.action

import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.dataInPort
import com.example.ottomatic.domain.registry.ConfigField
import com.example.ottomatic.domain.registry.ConfigFieldType
import com.example.ottomatic.engine.Action
import com.example.ottomatic.engine.ExecutionContext
import com.example.ottomatic.engine.NodeOutput
import com.example.ottomatic.engine.actionNode

data class StopInput(val reason: String)

/**
 * Action for `action.stop`. Halts the current execution chain: the
 * [com.example.ottomatic.engine.WorkflowExecutor] stops following `execOut`
 * ports after this node runs. Optionally logs a reason (wired from upstream
 * data or a static literal) before halting. Pairs with
 * `trigger.macro_finished`.
 *
 * The halt is scoped to the current trigger's traversal — sibling chains
 * triggered by independent events are unaffected.
 */
class StopAction : Action<StopInput, Unit> {

    override val definition = actionNode<StopInput, Unit>(
        typeId = "action.stop",
        displayName = "Stop Macro",
        description = "Halts the current execution chain (stops following connected actions)",
        category = NodeCategory.FLOW_CONTROL,
        iconKey = "bolt",
        dataInputs = listOf(dataInPort<String>("reason")),
        configFields = listOf(
            ConfigField(
                key = "reason",
                label = "Reason (optional, logged before halting)",
                type = ConfigFieldType.STR,
            ),
        ),
        decode = { input -> StopInput(input.text("reason")) },
        encodeData = { emptyMap() },
    )

    override suspend fun execute(input: StopInput, context: ExecutionContext): NodeOutput<Unit> {
        if (input.reason.isNotBlank()) context.log("Stop: ${input.reason}")
        return NodeOutput(Unit, halt = true)
    }
}
