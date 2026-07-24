package com.example.ottomatic.engine.action

import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.dataOut
import com.example.ottomatic.domain.model.items.BluetoothState
import com.example.ottomatic.domain.model.schema.Item
import com.example.ottomatic.domain.registry.ConfigField
import com.example.ottomatic.domain.registry.ConfigFieldType
import com.example.ottomatic.engine.Action
import com.example.ottomatic.engine.ExecutionContext
import com.example.ottomatic.engine.NodeOutput
import com.example.ottomatic.engine.actionNode

data class BluetoothInput(val enabled: Boolean)

/**
 * Action for `action.bluetooth`. Toggles the device Bluetooth radio and
 * reports the resulting [BluetoothState] on its `state` data port.
 *
 * Requires `BLUETOOTH_CONNECT` on API 31+; when missing or unavailable,
 * [BluetoothState.changed] is `false`. Pairs with `trigger.bluetooth` and
 * `trigger.bluetooth_connect`.
 */
class BluetoothAction : Action<BluetoothInput, BluetoothState> {

    override val definition = actionNode<BluetoothInput, BluetoothState>(
        typeId = "action.bluetooth",
        displayName = "Toggle Bluetooth",
        description = "Turns Bluetooth on or off and reports the resulting state",
        category = NodeCategory.DEVICE_SETTINGS,
        iconKey = "bluetooth",
        dataOutputs = listOf(dataOut<BluetoothState>("state")),
        configFields = listOf(
            ConfigField(
                key = "state",
                label = "State",
                type = ConfigFieldType.ENUM(options = listOf("on", "off")),
                defaultValue = "on",
            ),
        ),
        decode = { input -> BluetoothInput(input.configString("state", "on") != "off") },
        encodeData = { state -> mapOf("state" to Item.of(state)) },
    )

    override suspend fun execute(input: BluetoothInput, context: ExecutionContext): NodeOutput<BluetoothState> {
        val result = context.systemServices.setBluetooth(input.enabled)
        return NodeOutput(
            BluetoothState(
                enabled = result?.enabled ?: input.enabled,
                changed = result?.changed ?: false,
            ),
        )
    }
}
