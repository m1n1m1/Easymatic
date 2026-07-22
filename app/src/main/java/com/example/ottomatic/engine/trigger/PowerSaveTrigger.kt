package com.example.ottomatic.engine.trigger

import com.example.ottomatic.core.trigger.TriggerSource
import com.example.ottomatic.domain.model.WorkflowNode
import kotlinx.coroutines.flow.Flow

/**
 * Trigger for `trigger.power_save`. Fires when power-save mode is toggled on
 * or off.
 *
 * Produces a typed [com.example.ottomatic.domain.model.items.SystemState] item
 * on the `state` data port.
 */
class PowerSaveTrigger : Trigger {

    override val typeId: String = TYPE_ID

    override fun activate(node: WorkflowNode, host: TriggerHost): Flow<TriggerEvent> =
        systemStateFlow(
            source = TriggerSource.DISPLAY,
            triggerType = "power_save",
            node = node,
            host = host,
        )

    companion object {
        const val TYPE_ID = "trigger.power_save"
    }
}
