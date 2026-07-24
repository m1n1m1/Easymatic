package com.example.ottomatic.engine.action

import com.example.ottomatic.engine.Action
import com.example.ottomatic.engine.ActionInput
import com.example.ottomatic.engine.ActionResult
import com.example.ottomatic.engine.ExecutionContext

/**
 * Action for `action.vibrate`. Vibrates the device for a fixed duration or a
 * long-off-long… pattern. Pure passthrough on exec — no data port. Requires a
 * vibrator; on failure the action still pulses `out`.
 *
 * - `duration` (INT, ms): used when `pattern` is empty (default `500`).
 * - `pattern` (STR, comma-separated ms pairs, optional): e.g. `"0,200,500,200"`
 *   alternates off/on durations.
 */
class VibrateAction : Action {

    override val typeId: String = TYPE_ID

    override suspend fun execute(input: ActionInput, context: ExecutionContext): ActionResult {
        val duration = input.config.int("duration", default = DEFAULT_DURATION)
        val patternStr = input.config.str("pattern", default = "")
        val pattern = parsePattern(patternStr)
        context.systemServices.vibrate(duration, pattern)
        return ActionResult.passthrough("out")
    }

    private fun parsePattern(str: String): List<Long> {
        if (str.isBlank()) return emptyList()
        return str.split(",").mapNotNull { it.trim().toLongOrNull() }
    }

    companion object {
        const val TYPE_ID = "action.vibrate"
        private const val DEFAULT_DURATION = 500
    }
}
