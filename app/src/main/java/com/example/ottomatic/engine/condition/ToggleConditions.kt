package com.example.ottomatic.engine.condition

import com.example.ottomatic.core.service.OnOff
import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.NodeIcon
import com.example.ottomatic.domain.model.config.Label
import com.example.ottomatic.engine.ConditionNode
import com.example.ottomatic.engine.ExecutionContext
import com.example.ottomatic.engine.NodeInput
import com.example.ottomatic.engine.conditionNode
import kotlinx.serialization.Serializable

/**
 * Shared config for the on/off device-state conditions: "is this subsystem in
 * state [state]?". One class serves Wi-Fi, Bluetooth, airplane mode, charging,
 * screen and DND, because the question is identical in each case and only the
 * reader differs.
 */
@Serializable
data class StateConfig(
    @Label("State") val state: OnOff = OnOff.ON,
)

/**
 * Base for a condition that compares one readable boolean against [StateConfig].
 *
 * A null reading — subsystem absent or permission missing — evaluates false and
 * logs. An unknowable state is not a passing one, so a gate never opens by
 * accident.
 */
abstract class BooleanStateCondition internal constructor() : ConditionNode<StateConfig> {

    /** The current value, or null when it cannot be read. */
    protected abstract fun read(context: ExecutionContext): Boolean?

    override suspend fun evaluate(
        config: StateConfig,
        input: NodeInput,
        context: ExecutionContext,
    ): Boolean {
        val actual = read(context)
        if (actual == null) {
            context.log("Condition ${definition.typeId.value}: state unavailable, treating as false")
            return false
        }
        return actual == config.state.enabled
    }
}

/** `condition.wifi` — whether Wi-Fi is on. */
class WifiCondition : BooleanStateCondition() {
    override val definition = conditionNode<StateConfig>(
        typeId = "condition.wifi",
        displayName = "Wi-Fi is",
        description = "Passes when Wi-Fi is currently in the selected state",
        category = NodeCategory.CONDITION_DEVICE,
        icon = NodeIcon.WIFI,
    )

    override fun read(context: ExecutionContext) = context.deviceState.isWifiEnabled()
}

/** `condition.bluetooth` — whether Bluetooth is on. */
class BluetoothCondition : BooleanStateCondition() {
    override val definition = conditionNode<StateConfig>(
        typeId = "condition.bluetooth",
        displayName = "Bluetooth is",
        description = "Passes when Bluetooth is currently in the selected state",
        category = NodeCategory.CONDITION_DEVICE,
        icon = NodeIcon.BLUETOOTH,
    )

    override fun read(context: ExecutionContext) = context.deviceState.isBluetoothEnabled()
}

/** `condition.airplane` — whether airplane mode is on. */
class AirplaneModeCondition : BooleanStateCondition() {
    override val definition = conditionNode<StateConfig>(
        typeId = "condition.airplane",
        displayName = "Airplane mode is",
        description = "Passes when airplane mode is currently in the selected state",
        category = NodeCategory.CONDITION_DEVICE,
        icon = NodeIcon.BOLT,
    )

    override fun read(context: ExecutionContext) = context.deviceState.isAirplaneMode()
}

/** `condition.charging` — whether the device is charging. */
class ChargingCondition : BooleanStateCondition() {
    override val definition = conditionNode<StateConfig>(
        typeId = "condition.charging",
        displayName = "Charging is",
        description = "Passes when the device is currently charging (or not)",
        category = NodeCategory.CONDITION_DEVICE,
        icon = NodeIcon.BATTERY_CHARGING,
    )

    override fun read(context: ExecutionContext) = context.deviceState.isCharging()
}

/** `condition.screen` — whether the screen is on. */
class ScreenCondition : BooleanStateCondition() {
    override val definition = conditionNode<StateConfig>(
        typeId = "condition.screen",
        displayName = "Screen is",
        description = "Passes when the screen is currently on (or off)",
        category = NodeCategory.CONDITION_DEVICE,
        icon = NodeIcon.BOLT,
    )

    override fun read(context: ExecutionContext) = context.deviceState.isScreenOn()
}

/** `condition.dnd` — whether Do-Not-Disturb is active. */
class DndCondition : BooleanStateCondition() {
    override val definition = conditionNode<StateConfig>(
        typeId = "condition.dnd",
        displayName = "Do Not Disturb is",
        description = "Passes when Do-Not-Disturb is currently in the selected state",
        category = NodeCategory.CONDITION_DEVICE,
        icon = NodeIcon.DND,
    )

    override fun read(context: ExecutionContext) = context.deviceState.isDndEnabled()
}
