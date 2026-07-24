package com.example.ottomatic.engine.action

import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.dataInPort
import com.example.ottomatic.domain.model.dataOut
import com.example.ottomatic.domain.model.items.CallInitiated
import com.example.ottomatic.domain.model.schema.Item
import com.example.ottomatic.domain.registry.ConfigField
import com.example.ottomatic.domain.registry.ConfigFieldType
import com.example.ottomatic.engine.Action
import com.example.ottomatic.engine.ExecutionContext
import com.example.ottomatic.engine.NodeOutput
import com.example.ottomatic.engine.actionNode

data class CallInput(val number: String)

/**
 * Action for `action.call`. Initiates a phone call to `number` via
 * `ACTION_CALL` (requires `CALL_PHONE`). `number` may be wired from upstream
 * data or set as a static literal. Reports [CallInitiated] on its `state`
 * data port.
 */
class CallAction : Action<CallInput, CallInitiated> {

    override val definition = actionNode<CallInput, CallInitiated>(
        typeId = "action.call",
        displayName = "Make Call",
        description = "Initiates a phone call to a number",
        category = NodeCategory.NOTIFICATIONS,
        iconKey = "bolt",
        dataInputs = listOf(dataInPort<String>("number")),
        dataOutputs = listOf(dataOut<CallInitiated>("state")),
        configFields = listOf(
            ConfigField(
                key = "number",
                label = "Number",
                type = ConfigFieldType.STR,
                defaultValue = "",
            ),
        ),
        decode = { input -> CallInput(input.text("number")) },
        encodeData = { state -> mapOf("state" to Item.of(state)) },
    )

    override suspend fun execute(input: CallInput, context: ExecutionContext): NodeOutput<CallInitiated> {
        val ok = context.systemServices.call(input.number)
        return NodeOutput(CallInitiated(number = input.number, initiated = ok))
    }
}
