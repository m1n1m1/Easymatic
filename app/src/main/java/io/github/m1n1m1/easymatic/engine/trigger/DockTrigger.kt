package io.github.m1n1m1.easymatic.engine.trigger

import io.github.m1n1m1.easymatic.core.trigger.TriggerSource
import io.github.m1n1m1.easymatic.domain.model.NodeCategory
import io.github.m1n1m1.easymatic.domain.model.WorkflowNode
import io.github.m1n1m1.easymatic.domain.model.items.SystemState
import io.github.m1n1m1.easymatic.engine.NodeOutput
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

/** The dock transitions `trigger.dock` can filter on. */
@Serializable
enum class DockEvent {
    DOCKED,
    UNDOCKED,
}

/**
 * Trigger for `trigger.dock`. Fires when the device is docked or undocked.
 *
 * Produces a typed [SystemState] item on the `state` data port.
 */
class DockTrigger : Trigger<EventFilter<DockEvent>, SystemState> {

    override val definition = systemStateDefinition<EventFilter<DockEvent>>(
        typeId = "trigger.dock",
        displayName = "Device Docked",
        description = "Starts when the device is docked or undocked",
        category = NodeCategory.CONNECTIVITY,
    )

    override fun activate(
        config: EventFilter<DockEvent>,
        node: WorkflowNode,
        host: TriggerHost,
    ): Flow<NodeOutput<SystemState>> = systemStateFlow(
        source = TriggerSource.HARDWARE,
        triggerType = "dock",
        host = host,
        event = config.event,
    )
}
