package com.example.ottomatic.engine.condition

import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.NodeIcon
import com.example.ottomatic.domain.model.config.ComparisonOperator
import com.example.ottomatic.domain.model.config.Label
import com.example.ottomatic.engine.ConditionNode
import com.example.ottomatic.engine.ExecutionContext
import com.example.ottomatic.engine.NodeInput
import com.example.ottomatic.engine.conditionNode
import kotlinx.serialization.Serializable

private const val DEFAULT_LEVEL = 50

/** Config for `condition.battery`. */
@Serializable
data class BatteryConditionConfig(
    @Label("Operator") val operator: ComparisonOperator = ComparisonOperator.GREATER_THAN_OR_EQUAL,
    @Label("Level (%)") val level: Int = DEFAULT_LEVEL,
)

/**
 * `condition.battery` — compares the current battery percentage against a
 * threshold.
 *
 * Reuses [ComparisonOperator.matches] rather than spelling out the comparison, so
 * this node and `condition.compare` cannot disagree about what "greater than"
 * means.
 */
class BatteryCondition : ConditionNode<BatteryConditionConfig> {

    override val definition = conditionNode<BatteryConditionConfig>(
        typeId = "condition.battery",
        displayName = "Battery level",
        description = "Passes when the battery percentage compares as configured against a threshold",
        category = NodeCategory.CONDITION_DEVICE,
        icon = NodeIcon.BATTERY_LEVEL,
    )

    override suspend fun evaluate(
        config: BatteryConditionConfig,
        input: NodeInput,
        context: ExecutionContext,
    ): Boolean {
        val level = context.deviceState.batteryLevel()
        if (level == null) {
            context.log("Condition condition.battery: battery level unavailable, treating as false")
            return false
        }
        return config.operator.matches(level.toString(), config.level.toString())
    }
}
