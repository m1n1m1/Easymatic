package com.example.ottomatic.engine.action

import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.NodeIcon
import com.example.ottomatic.domain.model.config.Label
import com.example.ottomatic.engine.Action
import com.example.ottomatic.engine.ExecutionContext
import com.example.ottomatic.engine.NodeOutput
import com.example.ottomatic.engine.effectNode
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable

/** Time unit of a [DelayConfig.duration]. */
@Serializable
enum class DelayUnit(val millis: Long) {
    SECONDS(1_000L),
    MINUTES(60_000L),
    HOURS(3_600_000L),
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
        delay(input.millis)
        return NodeOutput(Unit)
    }
}
