package com.example.ottomatic.engine.trigger

import com.example.ottomatic.core.trigger.TriggerSource
import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.WorkflowNode
import com.example.ottomatic.domain.model.dataOut
import com.example.ottomatic.domain.model.items.MediaEvent
import com.example.ottomatic.domain.model.schema.Item
import com.example.ottomatic.domain.registry.ConfigField
import com.example.ottomatic.domain.registry.ConfigFieldType
import com.example.ottomatic.engine.NodeOutput
import com.example.ottomatic.engine.triggerNode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map

/**
 * Trigger for `trigger.media_mount`. Fires when external media is mounted,
 * unmounted, or ejected. The mount path is carried in the `detail` field.
 *
 * Produces a typed [MediaEvent] item on the `media` data port.
 */
class MediaMountTrigger : Trigger<MediaEvent> {

    override val definition = triggerNode<MediaEvent>(
        typeId = "trigger.media_mount",
        displayName = "Media Mounted / Unmounted",
        description = "Starts when external media is mounted, unmounted or ejected",
        category = NodeCategory.PHONE_MEDIA,
        iconKey = "bolt",
        dataOutputs = listOf(dataOut<MediaEvent>("media")),
        configFields = listOf(
            ConfigField(
                key = CONFIG_EVENT,
                label = "Event",
                type = ConfigFieldType.ENUM(options = listOf("any", "mounted", "unmounted", "ejected")),
                defaultValue = DEFAULT_EVENT,
            ),
        ),
        encodeData = { media -> mapOf("media" to Item.of(media)) },
    )

    override fun activate(node: WorkflowNode, host: TriggerHost): Flow<NodeOutput<MediaEvent>> =
        host.busEvents()
            .filter { it.source == TriggerSource.MEDIA }
            .filter { it.payload[KEY_TRIGGER_TYPE] == "media_mount" }
            .filter { event ->
                val filter = node.config[CONFIG_EVENT]?.takeIf { it.isNotBlank() } ?: DEFAULT_EVENT
                filter == DEFAULT_EVENT || filter == event.payload[KEY_EVENT]
            }
            .map { event ->
                NodeOutput(
                    MediaEvent(
                        event = event.payload[KEY_EVENT].orEmpty(),
                        detail = event.payload[KEY_DETAIL].orEmpty(),
                        timestamp = event.payload[KEY_TIMESTAMP]?.toLongOrNull() ?: event.firedAtEpochMs,
                    ),
                )
            }
}
