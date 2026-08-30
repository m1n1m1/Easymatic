package io.github.m1n1m1.easymatic.engine.trigger

import io.github.m1n1m1.easymatic.core.trigger.TriggerSource
import io.github.m1n1m1.easymatic.domain.model.NodeCategory
import io.github.m1n1m1.easymatic.domain.model.NodeIcon
import io.github.m1n1m1.easymatic.domain.model.WorkflowNode
import io.github.m1n1m1.easymatic.domain.model.dataOut
import io.github.m1n1m1.easymatic.domain.model.items.BatteryState
import io.github.m1n1m1.easymatic.engine.NodeOutput
import io.github.m1n1m1.easymatic.engine.triggerNode
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
