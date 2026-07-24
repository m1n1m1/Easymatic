package com.example.ottomatic.engine.action

import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.dataInPort
import com.example.ottomatic.domain.registry.ConfigField
import com.example.ottomatic.domain.registry.ConfigFieldType
import com.example.ottomatic.engine.Action
import com.example.ottomatic.engine.ExecutionContext
import com.example.ottomatic.engine.NodeOutput
import com.example.ottomatic.engine.actionNode

data class LogInput(val message: String)

/**
 * Action for `action.log`. Writes a single message to the engine log; the
 * message may be wired from upstream data or set as a static literal. Pulses
 * `out` with no data — useful for debugging and audit trails.
 */
class LogAction : Action<LogInput, Unit> {

    override val definition = actionNode<LogInput, Unit>(
        typeId = "action.log",
        displayName = "Log Message",
        description = "Writes a message to the engine log",
        category = NodeCategory.FLOW_CONTROL,
        iconKey = "bolt",
        dataInputs = listOf(dataInPort<String>("message")),
        configFields = listOf(
            ConfigField(
                key = "message",
                label = "Message",
                type = ConfigFieldType.MULTILINE,
                defaultValue = "",
            ),
        ),
        decode = { input -> LogInput(input.text("message")) },
        encodeData = { emptyMap() },
    )

    override suspend fun execute(input: LogInput, context: ExecutionContext): NodeOutput<Unit> {
        context.log(input.message)
        return NodeOutput(Unit)
    }
}
