package com.example.ottomatic.engine.action

import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.registry.ConfigField
import com.example.ottomatic.domain.registry.ConfigFieldType
import com.example.ottomatic.engine.Action
import com.example.ottomatic.engine.ExecutionContext
import com.example.ottomatic.engine.NodeOutput
import com.example.ottomatic.engine.actionNode

data class VibrateInput(val duration: Int, val pattern: String)

/**
 * Action for `action.vibrate`. Vibrates the device for a fixed duration or a
 * long-off-long… pattern. Pure passthrough on exec — no data port. Requires a
 * vibrator; on failure the action still pulses `out`.
 *
 * - `duration` (INT, ms): used when `pattern` is empty (default `500`).
 * - `pattern` (STR, comma-separated ms pairs, optional): e.g. `"0,200,500,200"`
 *   alternates off/on durations.
 */
class VibrateAction : Action<VibrateInput, Unit> {

    override val definition = actionNode<VibrateInput, Unit>(
        typeId = "action.vibrate",
        displayName = "Vibrate",
        description = "Vibrates the device for a duration or pattern",
        category = NodeCategory.NOTIFICATIONS,
        iconKey = "bolt",
        configFields = listOf(
            ConfigField(
                key = "duration",
                label = "Duration (ms, used when pattern is empty)",
                type = ConfigFieldType.INT,
                defaultValue = "500",
            ),
            ConfigField(
                key = "pattern",
                label = "Pattern (comma-separated ms, optional, e.g. 0,200,500,200)",
                type = ConfigFieldType.STR,
            ),
        ),
        decode = { input -> VibrateInput(input.configInt("duration", 500), input.configString("pattern")) },
        encodeData = { emptyMap() },
    )

    override suspend fun execute(input: VibrateInput, context: ExecutionContext): NodeOutput<Unit> {
        context.systemServices.vibrate(input.duration, parsePattern(input.pattern))
        return NodeOutput(Unit)
    }

    private fun parsePattern(str: String): List<Long> {
        if (str.isBlank()) return emptyList()
        return str.split(",").mapNotNull { it.trim().toLongOrNull() }
    }
}
