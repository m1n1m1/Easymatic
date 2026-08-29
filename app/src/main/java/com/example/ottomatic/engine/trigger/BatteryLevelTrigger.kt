package com.example.ottomatic.engine.trigger

import com.example.ottomatic.core.trigger.TriggerSource
import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.NodeIcon
import com.example.ottomatic.domain.model.WorkflowNode
import com.example.ottomatic.domain.model.config.Hint
import com.example.ottomatic.domain.model.config.Label
import com.example.ottomatic.domain.model.dataOut
import com.example.ottomatic.domain.model.items.BatteryState
import com.example.ottomatic.engine.NodeOutput
import com.example.ottomatic.engine.triggerNode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable

/** Which way the battery level must cross the threshold to fire. */
@Serializable
enum class BatteryDirection {
    BELOW,
    ABOVE,
}

/** Config for `trigger.battery_level`. */
@Serializable
data class BatteryLevelConfig(
    @Label("Direction") val direction: BatteryDirection = BatteryDirection.BELOW,
    @Label("Threshold (0-100)") val level: Int = DEFAULT_THRESHOLD,
    @Label("Poll interval")
    @Hint("minutes, minimum 15")
    val intervalMinutes: Long = DEFAULT_INTERVAL_MINUTES,
)

private const val DEFAULT_THRESHOLD = 20

/**
 * Trigger for `trigger.battery_level`. Arms a periodic
 * [com.example.ottomatic.data.trigger.BatteryLevelWorker] via the host when
 * collection starts, surfaces matching bus events, and cancels the poll when
 * the flow is cancelled.
 *
 * Polling is WorkManager-backed (15-minute floor) so it runs from a killed
 * app. Hysteresis is applied worker-side so a level hovering near the
 * threshold does not flap.
 *
 * Produces a typed [BatteryState] item on the `state` data port.
 */
class BatteryLevelTrigger : Trigger<BatteryLevelConfig, BatteryState> {

    override val definition = triggerNode<BatteryLevelConfig, BatteryState>(
        typeId = "trigger.battery_level",
        displayName = "Battery Level",
        description = "Starts when the battery level crosses a threshold (polls in the background)",
        category = NodeCategory.POWER_BATTERY,
        icon = NodeIcon.BATTERY_LEVEL,
        output = dataOut<BatteryState>("state", label = "State"),
    )

    override fun activate(
        config: BatteryLevelConfig,
        node: WorkflowNode,
        host: TriggerHost,
    ): Flow<NodeOutput<BatteryState>> = flow {
        val handle = host.armBatteryLevelPoll(node.id, config.intervalMinutes, config.direction, config.level)
        try {
            host.busEvents()
                .filter {
                    it.source == TriggerSource.BATTERY &&
                        it.triggerNodeId == node.id &&
                        it.payload[KEY_EVENT] == EVENT_LEVEL_POLL
                }
                .map { event -> NodeOutput(event.toBatteryState()) }
                .collect { emit(it) }
        } finally {
            handle.cancel()
        }
    }

    companion object {
        /** The `event` payload value emitted by the battery-level poll worker. */
        const val EVENT_LEVEL_POLL = "level_poll"
    }
}

/**
 * Maps a battery bus event to a typed [BatteryState]. Shared by
 * `trigger.battery_level` and `trigger.charging`, whose producers in `data/`
 * emit the same payload keys.
 */
internal fun com.example.ottomatic.core.trigger.TriggerEvent.toBatteryState(): BatteryState = BatteryState(
    isCharging = payload[KEY_IS_CHARGING]?.toBooleanStrictOrNull() ?: false,
    level = payload[KEY_LEVEL]?.toIntOrNull() ?: UNKNOWN_LEVEL,
    plugged = payload[KEY_PLUGGED]?.takeIf { it.isNotBlank() },
    event = payload[KEY_EVENT].orEmpty(),
    timestamp = this.timestamp,
)

// Must match the payload keys emitted by `BatteryLevelWorker` / `ChargingReceiver` in `data/`.
internal const val KEY_LEVEL = "level"
internal const val KEY_IS_CHARGING = "isCharging"
internal const val KEY_PLUGGED = "plugged"

private const val UNKNOWN_LEVEL = -1
