package io.github.m1n1m1.easymatic.engine.trigger

import io.github.m1n1m1.easymatic.core.trigger.TriggerSource
import io.github.m1n1m1.easymatic.domain.model.NodeCategory
import io.github.m1n1m1.easymatic.domain.model.WorkflowNode
import io.github.m1n1m1.easymatic.domain.model.config.NoConfig
import io.github.m1n1m1.easymatic.domain.model.items.SystemState
import io.github.m1n1m1.easymatic.engine.NodeOutput
import kotlinx.coroutines.flow.Flow

/**
 * Trigger for `trigger.locale_change`. Fires when the device locale changes.
 *
 * Produces a typed [SystemState] item on the `state` data port.
 */
class LocaleChangeTrigger : Trigger<NoConfig, SystemState> {

    override val definition = systemStateDefinition<NoConfig>(
        typeId = "trigger.locale_change",
        displayName = "Locale Changed",
        description = "Starts when the device locale changes",
        category = NodeCategory.DEVICE_STATE,
    )

    override fun activate(
        config: NoConfig,
        node: WorkflowNode,
        host: TriggerHost,
    ): Flow<NodeOutput<SystemState>> = systemStateFlow(
        source = TriggerSource.SYSTEM,
        triggerType = "locale_change",
        host = host,
    )
}
