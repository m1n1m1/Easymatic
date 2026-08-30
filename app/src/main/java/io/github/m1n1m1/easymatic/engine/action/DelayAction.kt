package io.github.m1n1m1.easymatic.engine.action

import io.github.m1n1m1.easymatic.domain.model.NodeCategory
import io.github.m1n1m1.easymatic.domain.model.NodeIcon
import io.github.m1n1m1.easymatic.domain.model.config.Label
import io.github.m1n1m1.easymatic.engine.Action
import io.github.m1n1m1.easymatic.engine.ExecutionContext
import io.github.m1n1m1.easymatic.engine.NodeOutput
import io.github.m1n1m1.easymatic.engine.effectNode
import kotlinx.serialization.Serializable

private const val MILLIS_PER_SECOND = 1_000L
private const val MILLIS_PER_MINUTE = 60 * MILLIS_PER_SECOND
private const val MILLIS_PER_HOUR = 60 * MILLIS_PER_MINUTE
private const val MILLIS_PER_DAY = 24 * MILLIS_PER_HOUR

/**
 * Time unit of a [DelayConfig.duration], shared with `action.wait_until`'s
 * duration mode so the two nodes offer the same list.
 */
@Serializable
enum class DelayUnit(val millis: Long) {
    SECONDS(MILLIS_PER_SECOND),
    MINUTES(MILLIS_PER_MINUTE),
    HOURS(MILLIS_PER_HOUR),
    DAYS(MILLIS_PER_DAY),
}

/** Config for `action.delay`. */
@Serializable
data class DelayConfig(
    @Label("Duration") val duration: Int = 5,
    @Label("Unit") val unit: DelayUnit = DelayUnit.SECONDS,
) {
    /** The configured delay in milliseconds. */
    val millis: Long get() = duration.toLong() * unit.millis
}

/**
 * Action for `action.delay`. Pauses execution for the configured duration
 * without blocking, then pulses the `out` execution port.
 *
 * The pause goes through [ExecutionContext.waits] rather than `delay` because
 * `delay` measures scheduler time, which does not advance while the CPU is
 * suspended: a two-hour wait taken overnight used to overrun by however long the
 * device stayed asleep. The waiting itself is otherwise unchanged — one `out`,
 * pulsed when the duration is up. Waiting for a *moment* rather than a duration,
 * and carrying on with the rest of the macro meanwhile, is `action.wait_until`.
 */
class DelayAction : Action<DelayConfig, Unit> {

    override val definition = effectNode<DelayConfig>(
        typeId = "action.delay",
        displayName = "Wait",
        description = "Pauses the workflow for a while",
        category = NodeCategory.TIMING,
        icon = NodeIcon.TIMER,
    )

    override suspend fun execute(input: DelayConfig, context: ExecutionContext): NodeOutput<Unit> {
        context.waits.awaitUntil(System.currentTimeMillis() + input.millis) {}
        return NodeOutput(Unit)
    }
}
