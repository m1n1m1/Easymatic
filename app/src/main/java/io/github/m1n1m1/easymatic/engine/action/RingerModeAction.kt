package io.github.m1n1m1.easymatic.engine.action

import io.github.m1n1m1.easymatic.core.service.LogLevel
import io.github.m1n1m1.easymatic.core.service.RingerMode
import io.github.m1n1m1.easymatic.domain.model.NodeCategory
import io.github.m1n1m1.easymatic.domain.model.NodeIcon
import io.github.m1n1m1.easymatic.domain.model.config.Label
import io.github.m1n1m1.easymatic.domain.model.dataOut
import io.github.m1n1m1.easymatic.domain.model.items.RingerModeState
import io.github.m1n1m1.easymatic.engine.Action
import io.github.m1n1m1.easymatic.engine.ExecutionContext
import io.github.m1n1m1.easymatic.engine.NodeOutput
import io.github.m1n1m1.easymatic.engine.actionNode
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
        permissions = listOf(DND_POLICY_PERMISSION),
        output = dataOut<RingerModeState>("state"),
    )

    override suspend fun execute(input: RingerModeConfig, context: ExecutionContext): NodeOutput<RingerModeState> {
        val result = context.systemServices.setRingerMode(input.mode)
        if (result?.changed != true) {
            context.log(
                "RingerMode: no device setting change was reported; check permissions and device restrictions",
                LogLevel.WARN,
            )
        }
        return NodeOutput(RingerModeState(mode = result?.mode ?: input.mode, changed = result?.changed ?: false))
    }
}
