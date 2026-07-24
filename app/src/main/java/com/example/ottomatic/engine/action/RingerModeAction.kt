package com.example.ottomatic.engine.action

import com.example.ottomatic.core.service.RingerMode
import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.NodeIcon
import com.example.ottomatic.domain.model.config.Label
import com.example.ottomatic.domain.model.dataOut
import com.example.ottomatic.domain.model.items.RingerModeState
import com.example.ottomatic.engine.Action
import com.example.ottomatic.engine.ExecutionContext
import com.example.ottomatic.engine.NodeOutput
import com.example.ottomatic.engine.actionNode
import kotlinx.serialization.Serializable

/** Config for `action.ringer_mode`. */
@Serializable
data class RingerModeConfig(
    @Label("Mode") val mode: RingerMode = RingerMode.NORMAL,
)

/**
 * Action for `action.ringer_mode`. Sets the ringer mode and reports the
 * resulting [RingerModeState] on its `state` data port.
 * [RingerMode.SILENT] requires `ACCESS_NOTIFICATION_POLICY` on API 21+. Pairs
 * with `trigger.ringer_mode`.
 */
class RingerModeAction : Action<RingerModeConfig, RingerModeState> {

    override val definition = actionNode<RingerModeConfig, RingerModeState>(
        typeId = "action.ringer_mode",
        displayName = "Set Ringer Mode",
        description = "Sets the ringer mode to normal, silent or vibrate",
        category = NodeCategory.DEVICE_SETTINGS,
        icon = NodeIcon.BOLT,
        output = dataOut<RingerModeState>("state"),
    )

    override suspend fun execute(input: RingerModeConfig, context: ExecutionContext): NodeOutput<RingerModeState> {
        val result = context.systemServices.setRingerMode(input.mode)
        return NodeOutput(RingerModeState(mode = result?.mode ?: input.mode, changed = result?.changed ?: false))
    }
}
