package com.example.ottomatic.engine.action

import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.dataOut
import com.example.ottomatic.domain.model.items.WifiState
import com.example.ottomatic.domain.model.schema.Item
import com.example.ottomatic.domain.registry.ConfigField
import com.example.ottomatic.domain.registry.ConfigFieldType
import com.example.ottomatic.engine.Action
import com.example.ottomatic.engine.ExecutionContext
import com.example.ottomatic.engine.NodeOutput
import com.example.ottomatic.engine.actionNode

data class WifiInput(val enabled: Boolean)

/**
 * Action for `action.wifi`. Toggles the device Wi-Fi state and reports the
 * resulting [WifiState] on its `state` data port.
 *
 * Note: `WifiManager.setWifiEnabled` is deprecated and returns false on
 * API 30+ where the user must toggle Wi-Fi manually. The [WifiState.changed]
 * flag reports whether the change was accepted.
 */
class WifiAction : Action<WifiInput, WifiState> {

    override val definition = actionNode<WifiInput, WifiState>(
        typeId = "action.wifi",
        displayName = "Toggle Wi-Fi",
        description = "Turns Wi-Fi on or off and reports the resulting state",
        category = NodeCategory.DEVICE_SETTINGS,
        iconKey = "wifi",
        dataOutputs = listOf(dataOut<WifiState>("state")),
        configFields = listOf(
            ConfigField(
                key = "state",
                label = "State",
                type = ConfigFieldType.ENUM(options = listOf("on", "off")),
                defaultValue = "on",
            ),
        ),
        decode = { input -> WifiInput(input.configString("state", "on") != "off") },
        encodeData = { state -> mapOf("state" to Item.of(state)) },
    )

    override suspend fun execute(input: WifiInput, context: ExecutionContext): NodeOutput<WifiState> {
        val result = context.systemServices.setWifi(input.enabled)
        return NodeOutput(WifiState(enabled = input.enabled, changed = result != null))
    }
}
