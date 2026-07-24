package com.example.ottomatic.engine.action

import com.example.ottomatic.domain.model.items.BrightnessState
import com.example.ottomatic.domain.model.schema.Item
import com.example.ottomatic.engine.Action
import com.example.ottomatic.engine.ActionInput
import com.example.ottomatic.engine.ActionResult
import com.example.ottomatic.engine.ExecutionContext

/**
 * Action for `action.brightness`. Sets the screen brightness to an absolute
 * value (0..255) or toggles auto-brightness, and reports the resulting
 * [BrightnessState] on its `state` data port. Requires `WRITE_SETTINGS`.
 */
class BrightnessAction : Action {

    override val typeId: String = TYPE_ID

    override suspend fun execute(input: ActionInput, context: ExecutionContext): ActionResult {
        val auto = input.config.bool("auto", default = false)
        val value = input.config.int("value", default = 128)
        val result = context.systemServices.setBrightness(value, auto)
        val state = BrightnessState(
            value = result?.value ?: value,
            auto = result?.auto ?: auto,
            changed = result?.changed ?: false,
        )
        return ActionResult(execOut = listOf("out"), dataOut = mapOf("state" to Item.of(state)))
    }

    companion object {
        const val TYPE_ID = "action.brightness"
    }
}
