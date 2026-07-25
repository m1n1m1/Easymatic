package com.example.ottomatic.engine.condition

import com.example.ottomatic.core.service.RingerMode
import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.NodeIcon
import com.example.ottomatic.domain.model.config.Label
import com.example.ottomatic.engine.ConditionNode
import com.example.ottomatic.engine.ExecutionContext
import com.example.ottomatic.engine.NodeInput
import com.example.ottomatic.engine.conditionNode
import kotlinx.serialization.Serializable

/** Config for `condition.ringer`. */
@Serializable
data class RingerConditionConfig(
    @Label("Mode") val mode: RingerMode = RingerMode.NORMAL,
)

/** `condition.ringer` — whether the device is in the selected ringer mode. */
class RingerModeCondition : ConditionNode<RingerConditionConfig> {

    override val definition = conditionNode<RingerConditionConfig>(
        typeId = "condition.ringer",
        displayName = "Ringer mode is",
        description = "Passes when the device ringer is currently in the selected mode",
        category = NodeCategory.CONDITION_DEVICE,
        icon = NodeIcon.VOLUME,
    )

    override suspend fun evaluate(
        config: RingerConditionConfig,
        input: NodeInput,
        context: ExecutionContext,
    ): Boolean {
        val mode = context.deviceState.ringerMode()
        if (mode == null) {
            context.log("Condition condition.ringer: ringer mode unavailable, treating as false")
            return false
        }
        return mode == config.mode
    }
}
