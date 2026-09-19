package io.github.m1n1m1.easymatic.engine.action

import io.github.m1n1m1.easymatic.core.service.LogLevel
import io.github.m1n1m1.easymatic.domain.model.PlatformWarning
import io.github.m1n1m1.easymatic.domain.model.NodeCategory
import io.github.m1n1m1.easymatic.domain.model.NodeIcon
import io.github.m1n1m1.easymatic.domain.model.dataOut
import io.github.m1n1m1.easymatic.domain.model.items.BluetoothState
import io.github.m1n1m1.easymatic.engine.Action
import io.github.m1n1m1.easymatic.engine.ExecutionContext
import io.github.m1n1m1.easymatic.engine.NodeOutput
import io.github.m1n1m1.easymatic.engine.actionNode

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
        description = "Requests Bluetooth on or off; Android 13+ blocks automatic changes for ordinary apps",
        platformWarnings = listOf(PlatformWarning.BLUETOOTH_TOGGLE),
        category = NodeCategory.DEVICE_SETTINGS,
        icon = NodeIcon.BLUETOOTH,
        permissions = listOf(BLUETOOTH_PERMISSION),
        output = dataOut<BluetoothState>("state"),
    )

    override suspend fun execute(input: ToggleConfig, context: ExecutionContext): NodeOutput<BluetoothState> {
        val result = context.systemServices.setBluetooth(input.enabled)
        if (result?.changed != true) {
            context.log(
                "Bluetooth change refused. Check Nearby devices permission. Android 13+ blocks automatic " +
                    "changes for ordinary apps; change Bluetooth in system settings.",
                LogLevel.WARN,
            )
        }
        return NodeOutput(
            BluetoothState(
                enabled = result?.enabled ?: context.deviceState.isBluetoothEnabled() ?: input.enabled,
                changed = result?.changed ?: false,
            ),
        )
    }
}
