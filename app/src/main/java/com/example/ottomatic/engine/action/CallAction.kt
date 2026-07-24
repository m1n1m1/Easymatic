package com.example.ottomatic.engine.action

import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.NodeIcon
import com.example.ottomatic.domain.model.config.Label
import com.example.ottomatic.domain.model.config.Wired
import com.example.ottomatic.domain.model.dataOut
import com.example.ottomatic.domain.model.items.CallInitiated
import com.example.ottomatic.engine.Action
import com.example.ottomatic.engine.ExecutionContext
import com.example.ottomatic.engine.NodeOutput
import com.example.ottomatic.engine.actionNode
import kotlinx.serialization.Serializable

/** Config for `action.call`. */
@Serializable
data class CallConfig(
    @Label("Number") @Wired val number: String = "",
)

/**
 * Action for `action.call`. Initiates a phone call via `ACTION_CALL` (requires
 * `CALL_PHONE`). The number may be wired from upstream data or set as a static
 * literal. Reports [CallInitiated] on its `state` data port.
 */
class CallAction : Action<CallConfig, CallInitiated> {

    override val definition = actionNode<CallConfig, CallInitiated>(
        typeId = "action.call",
        displayName = "Make Call",
        description = "Initiates a phone call to a number",
        category = NodeCategory.NOTIFICATIONS,
        icon = NodeIcon.BOLT,
        output = dataOut<CallInitiated>("state"),
    )

    override suspend fun execute(input: CallConfig, context: ExecutionContext): NodeOutput<CallInitiated> {
        val initiated = context.systemServices.call(input.number)
        return NodeOutput(CallInitiated(number = input.number, initiated = initiated))
    }
}
