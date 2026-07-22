package com.example.ottomatic.engine.trigger

import com.example.ottomatic.core.trigger.TriggerSource
import com.example.ottomatic.domain.model.WorkflowNode
import kotlinx.coroutines.flow.Flow

/**
 * Trigger for `trigger.timezone_change`. Fires when the device timezone changes.
 * The new timezone ID is carried in the `detail` field.
 *
 * Produces a typed [com.example.ottomatic.domain.model.items.SystemState] item
 * on the `state` data port.
 */
class TimezoneChangeTrigger : Trigger {

    override val typeId: String = TYPE_ID

    override fun activate(node: WorkflowNode, host: TriggerHost): Flow<TriggerEvent> =
        systemStateFlow(
            source = TriggerSource.SYSTEM,
            triggerType = "timezone_change",
            node = node,
            host = host,
        )

    companion object {
        const val TYPE_ID = "trigger.timezone_change"
    }
}
