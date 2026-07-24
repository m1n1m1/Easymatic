package com.example.ottomatic.engine.action

import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.registry.ConfigField
import com.example.ottomatic.domain.registry.ConfigFieldType
import com.example.ottomatic.engine.Action
import com.example.ottomatic.engine.ExecutionContext
import com.example.ottomatic.engine.NodeOutput
import com.example.ottomatic.engine.actionNode
import kotlinx.coroutines.delay

data class DelayInput(val duration: Int, val unit: String)

/**
 * Action for `action.delay`. Pauses execution for the configured duration
 * without blocking, then pulses the `out` execution port.
 */
class DelayAction : Action<DelayInput, Unit> {

    override val definition = actionNode<DelayInput, Unit>(
        typeId = "action.delay",
        displayName = "Wait",
        description = "Pauses the workflow for a while",
        category = NodeCategory.TIMING,
        iconKey = "timer",
        configFields = listOf(
            ConfigField(
                key = "duration",
                label = "Duration",
                type = ConfigFieldType.INT,
                defaultValue = "5",
            ),
            ConfigField(
                key = "unit",
                label = "Unit",
                type = ConfigFieldType.ENUM(options = listOf("seconds", "minutes", "hours")),
                defaultValue = "seconds",
            ),
        ),
        decode = { input -> DelayInput(input.configInt("duration", 5), input.configString("unit", "seconds")) },
        encodeData = { emptyMap() },
    )

    override suspend fun execute(input: DelayInput, context: ExecutionContext): NodeOutput<Unit> {
        val duration = input.duration.toLong()
        val millis = when (input.unit) {
            "seconds" -> duration * SECONDS_TO_MS
            "minutes" -> duration * MINUTES_TO_MS
            "hours" -> duration * HOURS_TO_MS
            else -> duration * SECONDS_TO_MS
        }
        delay(millis)
        return NodeOutput(Unit)
    }

    companion object {
        private const val SECONDS_TO_MS = 1000L
        private const val MINUTES_TO_MS = 60_000L
        private const val HOURS_TO_MS = 3_600_000L
    }
}
