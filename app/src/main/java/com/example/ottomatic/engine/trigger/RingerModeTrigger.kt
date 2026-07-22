package com.example.ottomatic.engine.trigger

import com.example.ottomatic.core.trigger.TriggerSource
import com.example.ottomatic.domain.model.WorkflowNode
import kotlinx.coroutines.flow.Flow

/**
 * Trigger for `trigger.ringer_mode`. Fires when the ringer mode changes
 * (normal / silent / vibrate).
 *
 * Produces a typed [com.example.ottomatic.domain.model.items.SystemState] item
 * on the `state` data port.
 */
class RingerModeTrigger : Trigger {

    override val typeId: String = TYPE_ID

    override fun activate(node: WorkflowNode, host: TriggerHost): Flow<TriggerEvent> =
        systemStateFlow(
            source = TriggerSource.DISPLAY,
            triggerType = "ringer_mode",
            node = node,
            host = host,
        )

    companion object {
        const val TYPE_ID = "trigger.ringer_mode"
    }
}
