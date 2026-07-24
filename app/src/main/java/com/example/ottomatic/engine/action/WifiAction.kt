package com.example.ottomatic.engine.action

import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.NodeIcon
import com.example.ottomatic.domain.model.dataOut
import com.example.ottomatic.domain.model.items.WifiState
import com.example.ottomatic.engine.Action
import com.example.ottomatic.engine.ExecutionContext
import com.example.ottomatic.engine.NodeOutput
import com.example.ottomatic.engine.actionNode

/**
 * Action for `action.wifi`. Toggles the device Wi-Fi state and reports the
 * resulting [WifiState] on its `state` data port.
 *
 * Note: `WifiManager.setWifiEnabled` is deprecated and returns false on
 * API 30+ where the user must toggle Wi-Fi manually. The [WifiState.changed]
 * flag reports whether the change was accepted.
 */
class WifiAction : Action<ToggleConfig, WifiState> {

    override val definition = actionNode<ToggleConfig, WifiState>(
        typeId = "action.wifi",
        displayName = "Toggle Wi-Fi",
        description = "Turns Wi-Fi on or off and reports the resulting state",
        category = NodeCategory.DEVICE_SETTINGS,
        icon = NodeIcon.WIFI,
        output = dataOut<WifiState>("state"),
    )

    override suspend fun execute(input: ToggleConfig, context: ExecutionContext): NodeOutput<WifiState> {
        val result = context.systemServices.setWifi(input.enabled)
        return NodeOutput(WifiState(enabled = input.enabled, changed = result != null))
    }
}
