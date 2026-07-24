package com.example.ottomatic.engine.trigger

import com.example.ottomatic.core.trigger.TriggerSource
import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.NodeIcon
import com.example.ottomatic.domain.model.WorkflowNode
import com.example.ottomatic.domain.model.dataOut
import com.example.ottomatic.domain.model.items.BatteryState
import com.example.ottomatic.engine.NodeOutput
import com.example.ottomatic.engine.triggerNode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable

/**
 * The charging transitions `trigger.charging` can filter on. Entry names match
 * the `event` payload values pushed by `ChargingReceiver` in `data/` (see
 * [payloadValue]).
 */
@Serializable
enum class ChargingEvent {
    CHARGING_STARTED,
    CHARGING_STOPPED,
}

/**
 * Trigger for `trigger.charging`. Listens to the bus for charging events
 * pushed by the manifest-registered `ChargingReceiver` (which arrive with the
 * sentinel node id `*`) and fans them out to every charging trigger node,
 * optionally filtering by transition.
 *
 * Produces a typed [BatteryState] item on the `state` data port.
 */
class ChargingTrigger : Trigger<EventFilter<ChargingEvent>, BatteryState> {

    override val definition = triggerNode<EventFilter<ChargingEvent>, BatteryState>(
        typeId = "trigger.charging",
        displayName = "Charging",
        description = "Starts when the device starts or stops charging",
        category = NodeCategory.POWER_BATTERY,
        icon = NodeIcon.BATTERY_CHARGING,
        output = dataOut<BatteryState>("state", label = "State"),
    )

    override fun activate(
        config: EventFilter<ChargingEvent>,
        node: WorkflowNode,
        host: TriggerHost,
    ): Flow<NodeOutput<BatteryState>> {
        val wanted = config.event?.payloadValue
        return host.busEvents()
            .filter { it.source == TriggerSource.BATTERY }
            .filter { it.payload[KEY_EVENT] in CHARGING_EVENTS }
            .filter { wanted == null || wanted == it.payload[KEY_EVENT] }
            .map { NodeOutput(it.toBatteryState()) }
    }

    private companion object {
        val CHARGING_EVENTS: Set<String> = ChargingEvent.entries.map { it.payloadValue }.toSet()
    }
}
