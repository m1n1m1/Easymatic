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
import kotlinx.coroutines.flow.map

/**
 * Trigger for `trigger.charging`. Listens to the bus for charging events
 * pushed by the manifest-registered `ChargingReceiver` (which arrive with the
 * sentinel node id `*`) and fans them out to every charging trigger node,
 * optionally filtering by event type (`any` / `started` / `stopped`).
 *
 * Produces a typed [BatteryState] item on the `state` data port.
 *
 * Payload contract with the receiver (string keys, mirror of `SmsTrigger`):
 * - `event` ∈ `"charging_started"`, `"charging_stopped"`
 * - `level`, `isCharging`, `plugged`, `timestamp`
 */
class ChargingTrigger : Trigger<BatteryState> {

    override val definition = triggerNode<BatteryState>(
        typeId = "trigger.charging",
        displayName = "Charging",
        description = "Starts when the device starts or stops charging",
        category = NodeCategory.POWER_BATTERY,
        iconKey = "battery_charging",
        dataOutputs = listOf(dataOut<BatteryState>("state")),
        configFields = listOf(
            ConfigField(
                key = CONFIG_EVENT,
                label = "Event",
                type = ConfigFieldType.ENUM(options = listOf("any", "started", "stopped")),
                defaultValue = DEFAULT_EVENT,
            ),
        ),
        encodeData = { state -> mapOf("state" to Item.of(state)) },
    )

    override fun activate(node: WorkflowNode, host: TriggerHost): Flow<NodeOutput<BatteryState>> =
        host.busEvents()
            .filter { it.source == TriggerSource.BATTERY }
            .filter { event ->
                val payloadEvent = event.payload[KEY_EVENT]
                payloadEvent == EVENT_CHARGING_STARTED || payloadEvent == EVENT_CHARGING_STOPPED
            }
            .filter { event ->
                val filter = node.config[CONFIG_EVENT]?.takeIf { it.isNotBlank() } ?: DEFAULT_EVENT
                filter == DEFAULT_EVENT || filter == event.payload[KEY_EVENT]
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

    companion object {
        const val CONFIG_EVENT = "event"
        const val DEFAULT_EVENT = "any"

        // Must match the payload keys emitted by `ChargingReceiver` in `data/`.
        const val KEY_EVENT = "event"
        const val KEY_LEVEL = "level"
        const val KEY_IS_CHARGING = "isCharging"
        const val KEY_PLUGGED = "plugged"
        const val KEY_TIMESTAMP = "timestamp"

        const val EVENT_CHARGING_STARTED = "charging_started"
        const val EVENT_CHARGING_STOPPED = "charging_stopped"
    }
}
