package com.example.ottomatic.engine.trigger

import com.example.ottomatic.core.trigger.TriggerSource
import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.WorkflowNode
import com.example.ottomatic.domain.model.items.SystemState
import com.example.ottomatic.engine.NodeOutput
import kotlinx.coroutines.flow.Flow

/**
 * Trigger for `trigger.ringer_mode`. Fires when the ringer mode changes
 * (normal / silent / vibrate).
 *
 * Produces a typed [SystemState] item on the `state` data port.
 */
class RingerModeTrigger : Trigger<SystemState> {

    override val definition = systemStateDefinition(
        typeId = "trigger.ringer_mode",
        displayName = "Ringer Mode Changed",
        description = "Starts when the ringer mode changes (normal, silent, vibrate)",
        category = NodeCategory.PHONE_MEDIA,
        eventFilterLabel = "Mode",
        eventFilterOptions = listOf("normal", "silent", "vibrate"),
    )

    override fun activate(node: WorkflowNode, host: TriggerHost): Flow<NodeOutput<SystemState>> =
        systemStateFlow(
            source = TriggerSource.DISPLAY,
            triggerType = "ringer_mode",
            node = node,
            host = host,
        )
}
