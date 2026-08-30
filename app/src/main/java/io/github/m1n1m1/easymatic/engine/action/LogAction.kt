package io.github.m1n1m1.easymatic.engine.action

import io.github.m1n1m1.easymatic.domain.model.NodeCategory
import io.github.m1n1m1.easymatic.domain.model.NodeIcon
import io.github.m1n1m1.easymatic.domain.model.config.Label
import io.github.m1n1m1.easymatic.domain.model.config.Multiline
import io.github.m1n1m1.easymatic.domain.model.config.Wired
import io.github.m1n1m1.easymatic.engine.Action
import io.github.m1n1m1.easymatic.engine.ExecutionContext
import io.github.m1n1m1.easymatic.engine.NodeOutput
import io.github.m1n1m1.easymatic.engine.effectNode
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
 * it logs at [io.github.m1n1m1.easymatic.core.service.LogLevel.INFO] and is visible
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
