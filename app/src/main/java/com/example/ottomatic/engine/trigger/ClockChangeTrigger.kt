package com.example.ottomatic.engine.trigger

import com.example.ottomatic.core.trigger.TriggerSource
import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.WorkflowNode
import com.example.ottomatic.domain.model.config.Label
import com.example.ottomatic.domain.model.items.SystemState
import com.example.ottomatic.engine.NodeOutput
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

/**
 * A change to the device's clock environment, as filtered by
 * `trigger.clock_changed`.
 */
@Serializable
enum class ClockChangeEvent {
    @Label("Date rolled over")
    DATE_CHANGED,

    @Label("Timezone changed")
    TIMEZONE_CHANGED,
}

/**
 * Trigger for `trigger.clock_changed`. Replaces the separate `date_change` and
 * `timezone_change` nodes, which were the same `NoConfig` → [SystemState]
 * broadcast trigger twice over; the distinction is now a filter rather than a
 * node, matching every other Tier 1 trigger.
 *
 * Leaving the filter unset fires on either event. For a timezone change the
 * new timezone id is carried in [SystemState.detail].
 */
class ClockChangeTrigger : Trigger<EventFilter<ClockChangeEvent>, SystemState> {

    override val definition = systemStateDefinition<EventFilter<ClockChangeEvent>>(
        typeId = "trigger.clock_changed",
        displayName = "Clock Changed",
        description = "Starts when the device date rolls over or the timezone changes",
        // Defaults to BOLT like the other Tier 1 broadcast triggers, which is
        // what both nodes this replaces used.
        category = NodeCategory.TIME_SCHEDULE,
    )

    override fun activate(
        config: EventFilter<ClockChangeEvent>,
        node: WorkflowNode,
        host: TriggerHost,
    ): Flow<NodeOutput<SystemState>> = systemStateFlow(
        source = TriggerSource.SYSTEM,
        triggerType = CLOCK_CHANGE_TYPE,
        host = host,
        event = config.event,
    )

    private companion object {
        /** The shared `triggerType` payload emitted by `SystemStateReceiver`. */
        const val CLOCK_CHANGE_TYPE = "clock_change"
    }
}
