package io.github.m1n1m1.easymatic.engine.action

import io.github.m1n1m1.easymatic.domain.model.NodeCategory
import io.github.m1n1m1.easymatic.domain.model.NodeIcon
import io.github.m1n1m1.easymatic.domain.model.config.Hint
import io.github.m1n1m1.easymatic.domain.model.config.Label
import io.github.m1n1m1.easymatic.engine.Action
import io.github.m1n1m1.easymatic.engine.ExecutionContext
import io.github.m1n1m1.easymatic.engine.NodeOutput
import io.github.m1n1m1.easymatic.engine.effectNode
import kotlinx.serialization.Serializable

/**
 * Config for `action.vibrate`. [durationMs] is used when [pattern] is empty;
 * otherwise [pattern] is read as comma-separated off/on millisecond pairs.
 */
@Serializable
data class VibrateConfig(
    @Label("Duration")
    @Hint("ms, used when pattern is empty")
    val durationMs: Int = DEFAULT_DURATION_MS,
    @Label("Pattern")
    @Hint("comma-separated ms, e.g. 0,200,500,200")
    val pattern: String = "",
) {
    /** The parsed [pattern], or empty when unset/unparseable. */
    val patternMillis: List<Long>
        get() = pattern.takeIf { it.isNotBlank() }
            ?.split(',')
            ?.mapNotNull { it.trim().toLongOrNull() }
            .orEmpty()
}

private const val DEFAULT_DURATION_MS = 500

/**
 * Action for `action.vibrate`. Vibrates the device for a fixed duration or a
 * long-off-long… pattern. Pure passthrough on exec — no data port. Requires a
 * vibrator; on failure the action still pulses `out`.
 */
class VibrateAction : Action<VibrateConfig, Unit> {

    override val definition = effectNode<VibrateConfig>(
        typeId = "action.vibrate",
        displayName = "Vibrate",
        description = "Vibrates the device for a duration or pattern",
        category = NodeCategory.NOTIFICATIONS,
        icon = NodeIcon.BOLT,
    )

    override suspend fun execute(input: VibrateConfig, context: ExecutionContext): NodeOutput<Unit> {
        context.systemServices.vibrate(input.durationMs, input.patternMillis)
        return NodeOutput(Unit)
    }
}
