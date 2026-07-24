package com.example.ottomatic.engine.action

import com.example.ottomatic.domain.model.items.TorchState
import com.example.ottomatic.domain.model.schema.Item
import com.example.ottomatic.engine.Action
import com.example.ottomatic.engine.ActionInput
import com.example.ottomatic.engine.ActionResult
import com.example.ottomatic.engine.ExecutionContext

/**
 * Action for `action.flashlight`. Toggles the camera torch (flashlight) on or
 * off and reports the resulting [TorchState] on its `state` data port.
 * Requires `CAMERA`; when no camera with a flash unit is available,
 * [TorchState.changed] is `false`.
 */
class FlashlightAction : Action {

    override val typeId: String = TYPE_ID

    override suspend fun execute(input: ActionInput, context: ExecutionContext): ActionResult {
        val enabled = input.config.str("state", default = "on") != "off"
        val result = context.systemServices.setTorch(enabled)
        val state = TorchState(enabled = result?.enabled ?: enabled, changed = result?.changed ?: false)
        return ActionResult(execOut = listOf("out"), dataOut = mapOf("state" to Item.of(state)))
    }

    companion object {
        const val TYPE_ID = "action.flashlight"
    }
}
