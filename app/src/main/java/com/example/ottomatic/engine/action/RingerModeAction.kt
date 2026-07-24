package com.example.ottomatic.engine.action

import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.dataOut
import com.example.ottomatic.domain.model.items.RingerModeState
import com.example.ottomatic.domain.model.schema.Item
import com.example.ottomatic.domain.registry.ConfigField
import com.example.ottomatic.domain.registry.ConfigFieldType
import com.example.ottomatic.engine.Action
import com.example.ottomatic.engine.ExecutionContext
import com.example.ottomatic.engine.NodeOutput
import com.example.ottomatic.engine.actionNode

data class RingerModeInput(val mode: String)

/**
 * Action for `action.ringer_mode`. Sets the ringer mode — `normal`, `silent`
 * or `vibrate` — and reports the resulting [RingerModeState] on its `state`
 * data port. Silent requires `ACCESS_NOTIFICATION_POLICY` on API 21+. Pairs
 * with `trigger.ringer_mode`.
 */
class RingerModeAction : Action<RingerModeInput, RingerModeState> {

    override val definition = actionNode<RingerModeInput, RingerModeState>(
        typeId = "action.ringer_mode",
        displayName = "Set Ringer Mode",
        description = "Sets the ringer mode to normal, silent or vibrate",
        category = NodeCategory.DEVICE_SETTINGS,
        iconKey = "bolt",
        dataOutputs = listOf(dataOut<RingerModeState>("state")),
        configFields = listOf(
            ConfigField(
                key = "mode",
                label = "Mode",
                type = ConfigFieldType.ENUM(options = listOf("normal", "silent", "vibrate")),
                defaultValue = "normal",
            ),
        ),
        decode = { input -> RingerModeInput(input.configString("mode", "normal")) },
        encodeData = { state -> mapOf("state" to Item.of(state)) },
    )

    override suspend fun execute(input: RingerModeInput, context: ExecutionContext): NodeOutput<RingerModeState> {
        val result = context.systemServices.setRingerMode(input.mode)
        return NodeOutput(RingerModeState(mode = result?.mode ?: input.mode, changed = result?.changed ?: false))
    }
}
