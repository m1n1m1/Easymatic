package io.github.m1n1m1.easymatic.engine.trigger

import io.github.m1n1m1.easymatic.core.trigger.TriggerSource
import io.github.m1n1m1.easymatic.domain.model.NodeCategory
import io.github.m1n1m1.easymatic.domain.model.WorkflowNode
import io.github.m1n1m1.easymatic.domain.model.config.NoConfig
import io.github.m1n1m1.easymatic.domain.model.items.SystemState
import io.github.m1n1m1.easymatic.engine.NodeOutput
import kotlinx.coroutines.flow.Flow

/**
 * Trigger for `trigger.shutdown`. Fires when the device is shutting down.
 *
 * Produces a typed [SystemState] item on the `state` data port.
 */
class ShutdownTrigger : Trigger<NoConfig, SystemState> {

    override val definition = systemStateDefinition<NoConfig>(
        typeId = "trigger.shutdown",
        displayName = "Device Shutting Down",
        description = "Starts when the device is shutting down",
        category = NodeCategory.POWER_BATTERY,
    )

    override fun activate(
        config: NoConfig,
        node: WorkflowNode,
        host: TriggerHost,
    ): Flow<NodeOutput<SystemState>> = systemStateFlow(
        source = TriggerSource.SYSTEM,
        triggerType = "shutdown",
        host = host,
    )
}
