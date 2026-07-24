package com.example.ottomatic.engine.trigger

import com.example.ottomatic.core.trigger.TriggerSource
import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.WorkflowNode
import com.example.ottomatic.domain.model.dataOut
import com.example.ottomatic.domain.model.items.BatteryState
import com.example.ottomatic.domain.model.schema.Item
import com.example.ottomatic.domain.registry.ConfigField
import com.example.ottomatic.domain.registry.ConfigFieldType
import com.example.ottomatic.engine.NodeOutput
import com.example.ottomatic.engine.triggerNode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

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
class BatteryLevelTrigger : Trigger<BatteryState> {

    override val definition = triggerNode<BatteryState>(
        typeId = "trigger.battery_level",
        displayName = "Battery Level",
        description = "Starts when the battery level crosses a threshold (polls in the background)",
        category = NodeCategory.POWER_BATTERY,
        iconKey = "battery_level",
        dataOutputs = listOf(dataOut<BatteryState>("state")),
        configFields = listOf(
            ConfigField(
                key = CONFIG_DIRECTION,
                label = "Direction",
                type = ConfigFieldType.ENUM(options = listOf("below", "above")),
                defaultValue = DEFAULT_DIRECTION,
            ),
            ConfigField(
                key = CONFIG_LEVEL,
                label = "Threshold (0-100)",
                type = ConfigFieldType.INT,
                defaultValue = "20",
            ),
            ConfigField(
                key = CONFIG_INTERVAL,
                label = "Poll interval (minutes, minimum 15)",
                type = ConfigFieldType.INT,
                defaultValue = "15",
            ),
        ),
        encodeData = { state -> mapOf("state" to Item.of(state)) },
    )

    override fun activate(node: WorkflowNode, host: TriggerHost): Flow<NodeOutput<BatteryState>> {
        val direction = node.config[CONFIG_DIRECTION]?.takeIf { it.isNotBlank() } ?: DEFAULT_DIRECTION
        val threshold = node.config[CONFIG_LEVEL]?.toIntOrNull() ?: DEFAULT_THRESHOLD
        val intervalMinutes = node.config[CONFIG_INTERVAL]?.toLongOrNull() ?: DEFAULT_INTERVAL_MINUTES
        return flow {
            val handle = host.armBatteryLevelPoll(node.id, intervalMinutes, direction, threshold)
            try {
                host.busEvents()
                    .filter {
                        it.source == TriggerSource.BATTERY &&
                            it.triggerNodeId == node.id &&
                            it.payload[KEY_EVENT] == EVENT_LEVEL_POLL
                    }
                    .map { event ->
                        NodeOutput(
                            BatteryState(
                                isCharging = event.payload[KEY_IS_CHARGING]?.toBooleanStrictOrNull() ?: false,
                                level = event.payload[KEY_LEVEL]?.toIntOrNull() ?: -1,
                                plugged = event.payload[KEY_PLUGGED]?.takeIf { it.isNotBlank() },
                                event = event.payload[KEY_EVENT].orEmpty(),
                                timestamp = event.payload[KEY_TIMESTAMP]?.toLongOrNull() ?: event.firedAtEpochMs,
                            ),
                        )
                    }
                    .collect { emit(it) }
            } finally {
                handle.cancel()
            }
        }
    }

    companion object {
        const val CONFIG_DIRECTION = "direction"
        const val CONFIG_LEVEL = "level"
        const val CONFIG_INTERVAL = "intervalMinutes"

        const val DEFAULT_DIRECTION = "below"
        const val DEFAULT_THRESHOLD = 20
        const val DEFAULT_INTERVAL_MINUTES = 15L

        // Must match the payload keys emitted by `BatteryLevelWorker` in `data/`.
        const val KEY_EVENT = "event"
        const val KEY_LEVEL = "level"
        const val KEY_IS_CHARGING = "isCharging"
        const val KEY_PLUGGED = "plugged"
        const val KEY_TIMESTAMP = "timestamp"

        const val EVENT_LEVEL_POLL = "level_poll"
    }
}
