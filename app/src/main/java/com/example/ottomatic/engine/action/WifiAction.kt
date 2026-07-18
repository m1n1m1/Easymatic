package com.example.ottomatic.engine.action

import com.example.ottomatic.domain.model.items.WifiState
import com.example.ottomatic.domain.model.schema.Item
import com.example.ottomatic.engine.Action
import com.example.ottomatic.engine.ActionInput
import com.example.ottomatic.engine.ActionResult
import com.example.ottomatic.engine.ExecutionContext

/**
 * Action for `action.wifi`. Toggles the device Wi-Fi state and reports the
 * resulting [WifiState] on its `state` data port.
 *
 * Note: `WifiManager.setWifiEnabled` is deprecated and returns false on
 * API 30+ where the user must toggle Wi-Fi manually. The [WifiState.changed]
 * flag reports whether the change was accepted.
 */
class WifiAction : Action {

    override val typeId: String = TYPE_ID

    override suspend fun execute(input: ActionInput, context: ExecutionContext): ActionResult {
        val enabled = input.config.str("state", default = "on") != "off"
        val result = context.systemServices.setWifi(enabled)
        val state = WifiState(enabled = enabled, changed = result != null)
        return ActionResult(execOut = listOf("out"), dataOut = mapOf("state" to Item.of(state)))
    }

    companion object {
        const val TYPE_ID = "action.wifi"
    }
}
