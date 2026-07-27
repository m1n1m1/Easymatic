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

/** The media keys `trigger.media_button` can filter on. */
@Serializable
enum class MediaButton {
    PLAY,
    PAUSE,
    PLAY_PAUSE,
    NEXT,
    PREVIOUS,
    STOP,
    HEADSET_HOOK,
}

/**
 * Trigger for `trigger.media_button`. Fires when a media button is pressed. The
 * key name is carried in [MediaEvent.detail], which is also what the optional
 * filter matches against.
 *
 * Produces a typed [MediaEvent] item on the `media` data port.
 */
class MediaButtonTrigger : Trigger<EventFilter<MediaButton>, MediaEvent> {

    override val definition = triggerNode<EventFilter<MediaButton>, MediaEvent>(
        typeId = "trigger.media_button",
        displayName = "Media Button Pressed",
        description = "Starts when a media button is pressed (play, pause, next, etc.)",
        category = NodeCategory.PHONE_MEDIA,
        icon = NodeIcon.BOLT,
        output = dataOut<MediaEvent>("media", label = "Media"),
    )

    override fun activate(
        config: EventFilter<MediaButton>,
        node: WorkflowNode,
        host: TriggerHost,
    ): Flow<NodeOutput<MediaEvent>> {
        val wanted = config.event?.payloadValue
        return host.busEvents()
            .filter { it.source == TriggerSource.MEDIA }
            .filter { it.payload[KEY_TRIGGER_TYPE] == TRIGGER_TYPE }
            .filter { wanted == null || wanted == it.payload[KEY_DETAIL] }
            .map { it.toMediaEvent() }
    }

    private companion object {
        const val TRIGGER_TYPE = "media_button"
    }
}

/** Maps a media bus event to a typed [MediaEvent]. */
internal fun com.example.ottomatic.core.trigger.TriggerEvent.toMediaEvent(): NodeOutput<MediaEvent> = NodeOutput(
    MediaEvent(
        event = payload[KEY_EVENT].orEmpty(),
        detail = payload[KEY_DETAIL].orEmpty(),
        timestamp = this.timestamp,
    ),
)
