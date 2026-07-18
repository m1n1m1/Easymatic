package com.example.ottomatic.engine.action

import com.example.ottomatic.engine.Action
import com.example.ottomatic.engine.ActionInput
import com.example.ottomatic.engine.ActionResult
import com.example.ottomatic.engine.ExecutionContext
import kotlinx.coroutines.delay

/**
 * Action for `action.delay`. Pauses execution for the configured duration
 * without blocking, then pulses the `out` execution port.
 */
class DelayAction : Action {

    override val typeId: String = TYPE_ID

    override suspend fun execute(input: ActionInput, context: ExecutionContext): ActionResult {
        val duration = input.config.int("duration", default = DEFAULT_DURATION).toLong()
        val unit = input.config.str("unit", default = "seconds")
        val millis = when (unit) {
            "seconds" -> duration * SECONDS_TO_MS
            "minutes" -> duration * MINUTES_TO_MS
            "hours" -> duration * HOURS_TO_MS
            else -> duration * SECONDS_TO_MS
        }
        delay(millis)
        return ActionResult.passthrough("out")
    }

    companion object {
        const val TYPE_ID = "action.delay"
        private const val DEFAULT_DURATION = 5
        private const val SECONDS_TO_MS = 1000L
        private const val MINUTES_TO_MS = 60_000L
        private const val HOURS_TO_MS = 3_600_000L
    }
}
