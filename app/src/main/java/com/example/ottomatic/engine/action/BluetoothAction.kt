package com.example.ottomatic.engine.action

import com.example.ottomatic.domain.model.items.BluetoothState
import com.example.ottomatic.domain.model.schema.Item
import com.example.ottomatic.engine.Action
import com.example.ottomatic.engine.ActionInput
import com.example.ottomatic.engine.ActionResult
import com.example.ottomatic.engine.ExecutionContext

/**
 * Action for `action.bluetooth`. Toggles the device Bluetooth radio and
 * reports the resulting [BluetoothState] on its `state` data port.
 *
 * Requires `BLUETOOTH_CONNECT` on API 31+; when missing or unavailable,
 * [BluetoothState.changed] is `false`. Pairs with `trigger.bluetooth` and
 * `trigger.bluetooth_connect`.
 */
class BluetoothAction : Action {

    override val typeId: String = TYPE_ID

    override suspend fun execute(input: ActionInput, context: ExecutionContext): ActionResult {
        val enabled = input.config.str("state", default = "on") != "off"
        val result = context.systemServices.setBluetooth(enabled)
        val state = BluetoothState(enabled = result?.enabled ?: enabled, changed = result?.changed ?: false)
        return ActionResult(execOut = listOf("out"), dataOut = mapOf("state" to Item.of(state)))
    }

    companion object {
        const val TYPE_ID = "action.bluetooth"
    }
}
