package com.example.ottomatic.engine.action

import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.NodeIcon
import com.example.ottomatic.domain.model.config.Label
import com.example.ottomatic.domain.model.dataOut
import com.example.ottomatic.domain.model.items.BrightnessState
import com.example.ottomatic.engine.Action
import com.example.ottomatic.engine.ExecutionContext
import com.example.ottomatic.engine.NodeOutput
import com.example.ottomatic.engine.actionNode
import kotlinx.serialization.Serializable

/** Config for `action.brightness`. */
@Serializable
data class BrightnessConfig(
    @Label("Auto brightness") val auto: Boolean = false,
    @Label("Value (0-255, only when auto is off)") val value: Int = DEFAULT_BRIGHTNESS,
)

private const val DEFAULT_BRIGHTNESS = 128

/**
 * Action for `action.brightness`. Sets the screen brightness to an absolute
 * value (0..255) or toggles auto-brightness, and reports the resulting
 * [BrightnessState] on its `state` data port. Requires `WRITE_SETTINGS`.
 */
class BrightnessAction : Action<BrightnessConfig, BrightnessState> {

    override val definition = actionNode<BrightnessConfig, BrightnessState>(
        typeId = "action.brightness",
        displayName = "Set Brightness",
        description = "Sets screen brightness (absolute value or auto)",
        category = NodeCategory.DEVICE_SETTINGS,
        icon = NodeIcon.BOLT,
        output = dataOut<BrightnessState>("state"),
    )

    override suspend fun execute(input: BrightnessConfig, context: ExecutionContext): NodeOutput<BrightnessState> {
        val result = context.systemServices.setBrightness(input.value, input.auto)
        return NodeOutput(
            BrightnessState(
                value = result?.value ?: input.value,
                auto = result?.auto ?: input.auto,
                changed = result?.changed ?: false,
            ),
        )
    }
}
