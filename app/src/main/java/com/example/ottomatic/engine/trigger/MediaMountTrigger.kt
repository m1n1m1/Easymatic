package com.example.ottomatic.engine.trigger

import com.example.ottomatic.core.trigger.TriggerSource
import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.NodeIcon
import com.example.ottomatic.domain.model.WorkflowNode
import com.example.ottomatic.domain.model.dataOut
import com.example.ottomatic.domain.model.items.MediaEvent
import com.example.ottomatic.engine.NodeOutput
import com.example.ottomatic.engine.triggerNode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable

/** The mount transitions `trigger.media_mount` can filter on. */
@Serializable
enum class MediaMountEvent {
    MOUNTED,
    UNMOUNTED,
    EJECTED,
}

/**
 * Trigger for `trigger.media_mount`. Fires when external media is mounted,
 * unmounted, or ejected. The mount path is carried in [MediaEvent.detail].
 *
 * Produces a typed [MediaEvent] item on the `media` data port.
 */
class MediaMountTrigger : Trigger<EventFilter<MediaMountEvent>, MediaEvent> {

    override val definition = triggerNode<EventFilter<MediaMountEvent>, MediaEvent>(
        typeId = "trigger.media_mount",
        displayName = "Media Mounted / Unmounted",
        description = "Starts when external media is mounted, unmounted or ejected",
        category = NodeCategory.PHONE_MEDIA,
        icon = NodeIcon.BOLT,
        output = dataOut<MediaEvent>("media", label = "Media"),
    )

    override fun activate(
        config: EventFilter<MediaMountEvent>,
        node: WorkflowNode,
        host: TriggerHost,
    ): Flow<NodeOutput<MediaEvent>> {
        val wanted = config.event?.payloadValue
        return host.busEvents()
            .filter { it.source == TriggerSource.MEDIA }
            .filter { it.payload[KEY_TRIGGER_TYPE] == TRIGGER_TYPE }
            .filter { wanted == null || wanted == it.payload[KEY_EVENT] }
            .map { it.toMediaEvent() }
    }

    private companion object {
        const val TRIGGER_TYPE = "media_mount"
    }
}
