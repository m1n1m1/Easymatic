package io.github.m1n1m1.easymatic.engine.trigger

import io.github.m1n1m1.easymatic.core.trigger.TriggerSource
import io.github.m1n1m1.easymatic.domain.model.NodeCategory
import io.github.m1n1m1.easymatic.domain.model.WorkflowNode
import io.github.m1n1m1.easymatic.domain.model.items.SystemState
import io.github.m1n1m1.easymatic.engine.NodeOutput
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

/** The ringer modes `trigger.ringer_mode` can filter on. */
@Serializable
enum class RingerModeEvent {
    NORMAL,
    SILENT,
    VIBRATE,
}

/**
 * Trigger for `trigger.ringer_mode`. Fires when the ringer mode changes.
 *
 * Produces a typed [SystemState] item on the `state` data port.
 */
class RingerModeTrigger : Trigger<EventFilter<RingerModeEvent>, SystemState> {

    override val definition = systemStateDefinition<EventFilter<RingerModeEvent>>(
        typeId = "trigger.ringer_mode",
        displayName = "Ringer Mode Changed",
        description = "Starts when the ringer mode changes (normal, silent, vibrate)",
        category = NodeCategory.PHONE_MEDIA,
    )

    override fun activate(
        config: EventFilter<RingerModeEvent>,
        node: WorkflowNode,
        host: TriggerHost,
    ): Flow<NodeOutput<SystemState>> = systemStateFlow(
        source = TriggerSource.DISPLAY,
        triggerType = "ringer_mode",
        host = host,
        event = config.event,
    )
}
