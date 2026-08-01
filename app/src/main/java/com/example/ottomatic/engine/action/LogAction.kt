package com.example.ottomatic.engine.action

import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.NodeIcon
import com.example.ottomatic.domain.model.config.Label
import com.example.ottomatic.domain.model.config.Multiline
import com.example.ottomatic.domain.model.config.Wired
import com.example.ottomatic.engine.Action
import com.example.ottomatic.engine.ExecutionContext
import com.example.ottomatic.engine.NodeOutput
import com.example.ottomatic.engine.effectNode
import kotlinx.serialization.Serializable

/** Config for `action.log`. */
@Serializable
data class LogConfig(
    @Label("Message") @Multiline @Wired val message: String = "",
)

/**
 * Action for `action.log`. Writes a single message to the workflow's console;
 * the message may be wired from upstream data or set as a static literal. Pulses
 * `out` with no data.
 *
 * The deliberate half of the console. Everything else there is something the
 * engine decided to say; this is the one line a user puts where they want it, so
 * it logs at [com.example.ottomatic.core.service.LogLevel.INFO] and is visible
 * at the console's default filter.
 */
class LogAction : Action<LogConfig, Unit> {

    override val definition = effectNode<LogConfig>(
        typeId = "action.log",
        displayName = "Log Message",
        description = "Writes a message to this workflow's console",
        category = NodeCategory.FLOW_CONTROL,
        icon = NodeIcon.BOLT,
    )

    override suspend fun execute(input: LogConfig, context: ExecutionContext): NodeOutput<Unit> {
        context.log(input.message)
        return NodeOutput(Unit)
    }
}
