package com.example.ottomatic.engine.action

import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.NodeIcon
import com.example.ottomatic.domain.model.dataOut
import com.example.ottomatic.domain.model.items.BluetoothState
import com.example.ottomatic.engine.Action
import com.example.ottomatic.engine.ExecutionContext
import com.example.ottomatic.engine.NodeOutput
import com.example.ottomatic.engine.actionNode

/**
 * Action for `action.bluetooth`. Toggles the device Bluetooth radio and
 * reports the resulting [BluetoothState] on its `state` data port.
 *
 * Requires `BLUETOOTH_CONNECT` on API 31+; when missing or unavailable,
 * [BluetoothState.changed] is `false`. Pairs with `trigger.bluetooth` and
 * `trigger.bluetooth_connect`.
 */
class BluetoothAction : Action<ToggleConfig, BluetoothState> {

    override val definition = actionNode<ToggleConfig, BluetoothState>(
        typeId = "action.bluetooth",
        displayName = "Toggle Bluetooth",
        description = "Turns Bluetooth on or off and reports the resulting state",
        category = NodeCategory.DEVICE_SETTINGS,
        icon = NodeIcon.BLUETOOTH,
        output = dataOut<BluetoothState>("state"),
    )

    override suspend fun execute(input: ToggleConfig, context: ExecutionContext): NodeOutput<BluetoothState> {
        val result = context.systemServices.setBluetooth(input.enabled)
        return NodeOutput(
            BluetoothState(
                enabled = result?.enabled ?: input.enabled,
                changed = result?.changed ?: false,
            ),
        )
    }
}
