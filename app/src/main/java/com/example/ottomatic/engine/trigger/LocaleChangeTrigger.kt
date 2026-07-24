package com.example.ottomatic.engine.trigger

import com.example.ottomatic.core.trigger.TriggerSource
import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.WorkflowNode
import com.example.ottomatic.domain.model.config.NoConfig
import com.example.ottomatic.domain.model.items.SystemState
import com.example.ottomatic.engine.NodeOutput
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
