package com.example.ottomatic.engine.action

import com.example.ottomatic.domain.model.items.RingerModeState
import com.example.ottomatic.domain.model.schema.Item
import com.example.ottomatic.engine.Action
import com.example.ottomatic.engine.ActionInput
import com.example.ottomatic.engine.ActionResult
import com.example.ottomatic.engine.ExecutionContext

/**
 * Action for `action.ringer_mode`. Sets the ringer mode — `normal`, `silent`
 * or `vibrate` — and reports the resulting [RingerModeState] on its `state`
 * data port. Silent requires `ACCESS_NOTIFICATION_POLICY` on API 21+. Pairs
 * with `trigger.ringer_mode`.
 */
class RingerModeAction : Action {

    override val typeId: String = TYPE_ID

    override suspend fun execute(input: ActionInput, context: ExecutionContext): ActionResult {
        val mode = input.config.str("mode", default = "normal")
        val result = context.systemServices.setRingerMode(mode)
        val state = RingerModeState(mode = result?.mode ?: mode, changed = result?.changed ?: false)
        return ActionResult(execOut = listOf("out"), dataOut = mapOf("state" to Item.of(state)))
    }

    companion object {
        const val TYPE_ID = "action.ringer_mode"
    }
}
