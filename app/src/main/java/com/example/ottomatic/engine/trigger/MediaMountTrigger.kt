package com.example.ottomatic.engine.trigger

import com.example.ottomatic.core.trigger.TriggerSource
import com.example.ottomatic.domain.model.WorkflowNode
import com.example.ottomatic.domain.model.items.MediaEvent
import com.example.ottomatic.domain.model.schema.Item
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map

/**
 * Trigger for `trigger.media_mount`. Fires when external media is mounted,
 * unmounted, or ejected. The mount path is carried in the `detail` field.
 *
 * Produces a typed [MediaEvent] item on the `media` data port.
 */
class MediaMountTrigger : Trigger {

    override val typeId: String = TYPE_ID

    override fun activate(node: WorkflowNode, host: TriggerHost): Flow<TriggerEvent> =
        host.busEvents()
            .filter { it.source == TriggerSource.MEDIA }
            .filter { it.payload[KEY_TRIGGER_TYPE] == "media_mount" }
            .filter { event ->
                val filter = node.config[CONFIG_EVENT]?.takeIf { it.isNotBlank() } ?: DEFAULT_EVENT
                filter == DEFAULT_EVENT || filter == event.payload[KEY_EVENT]
            }
            .map { event ->
                val mediaEvent = MediaEvent(
                    event = event.payload[KEY_EVENT].orEmpty(),
                    detail = event.payload[KEY_DETAIL].orEmpty(),
                    timestamp = event.payload[KEY_TIMESTAMP]?.toLongOrNull() ?: event.firedAtEpochMs,
                )
                TriggerEvent(
                    triggerNodeId = node.id,
                    dataOut = mapOf("media" to Item.of(mediaEvent)),
                )
            }

    companion object {
        const val TYPE_ID = "trigger.media_mount"
    }
}
