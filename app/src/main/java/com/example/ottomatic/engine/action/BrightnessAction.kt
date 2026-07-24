package com.example.ottomatic.engine.action

import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.dataOut
import com.example.ottomatic.domain.model.items.BrightnessState
import com.example.ottomatic.domain.model.schema.Item
import com.example.ottomatic.domain.registry.ConfigField
import com.example.ottomatic.domain.registry.ConfigFieldType
import com.example.ottomatic.engine.Action
import com.example.ottomatic.engine.ExecutionContext
import com.example.ottomatic.engine.NodeOutput
import com.example.ottomatic.engine.actionNode

data class BrightnessInput(val auto: Boolean, val value: Int)

/**
 * Action for `action.brightness`. Sets the screen brightness to an absolute
 * value (0..255) or toggles auto-brightness, and reports the resulting
 * [BrightnessState] on its `state` data port. Requires `WRITE_SETTINGS`.
 */
class BrightnessAction : Action<BrightnessInput, BrightnessState> {

    override val definition = actionNode<BrightnessInput, BrightnessState>(
        typeId = "action.brightness",
        displayName = "Set Brightness",
        description = "Sets screen brightness (absolute value or auto)",
        category = NodeCategory.DEVICE_SETTINGS,
        iconKey = "bolt",
        dataOutputs = listOf(dataOut<BrightnessState>("state")),
        configFields = listOf(
            ConfigField(
                key = "auto",
                label = "Auto brightness",
                type = ConfigFieldType.ENUM(options = listOf("true", "false")),
                defaultValue = "false",
            ),
            ConfigField(
                key = "value",
                label = "Value (0-255, only when auto = false)",
                type = ConfigFieldType.INT,
                defaultValue = "128",
            ),
        ),
        decode = { input -> BrightnessInput(input.configBoolean("auto"), input.configInt("value", 128)) },
        encodeData = { state -> mapOf("state" to Item.of(state)) },
    )

    override suspend fun execute(input: BrightnessInput, context: ExecutionContext): NodeOutput<BrightnessState> {
        val result = context.systemServices.setBrightness(input.value, input.auto)
        val state = BrightnessState(
            value = result?.value ?: input.value,
            auto = result?.auto ?: input.auto,
            changed = result?.changed ?: false,
        )
        return NodeOutput(state)
    }
}
