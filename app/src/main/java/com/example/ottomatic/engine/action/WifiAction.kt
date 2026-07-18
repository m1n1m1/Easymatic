package com.example.ottomatic.engine.action

import com.example.ottomatic.engine.Action
import com.example.ottomatic.engine.ActionInput
import com.example.ottomatic.engine.ActionResult
import com.example.ottomatic.engine.ExecutionContext

/**
 * Action for `action.wifi`. Toggles the device Wi-Fi state.
 *
 * Note: `WifiManager.setWifiEnabled` is deprecated and returns false on
 * API 30+ where the user must toggle Wi-Fi manually. The result payload
 * reports whether the change was accepted.
 */
class WifiAction : Action {

    override val typeId: String = TYPE_ID

    override suspend fun execute(input: ActionInput, context: ExecutionContext): ActionResult {
        val enabled = input.node.config["state"] != "off"
        val result = context.systemServices.setWifi(enabled)
        val extra = mapOf("wifiChanged" to (result != null).toString())
        return ActionResult(mapOf(0 to input.payload + extra))
    }

    companion object {
        const val TYPE_ID = "action.wifi"
    }
}
