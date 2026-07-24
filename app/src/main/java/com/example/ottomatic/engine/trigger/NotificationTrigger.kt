package com.example.ottomatic.engine.trigger

import com.example.ottomatic.core.trigger.TriggerSource
import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.WorkflowNode
import com.example.ottomatic.domain.model.dataOut
import com.example.ottomatic.domain.model.items.NotificationEvent
import com.example.ottomatic.domain.model.schema.Item
import com.example.ottomatic.domain.registry.ConfigField
import com.example.ottomatic.domain.registry.ConfigFieldType
import com.example.ottomatic.engine.NodeOutput
import com.example.ottomatic.engine.triggerNode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map

/**
 * Trigger for `trigger.notification`. Listens to the bus for notification
 * events and optionally filters by app package.
 *
 * Produces a typed [NotificationEvent] item on the `notification` data port.
 */
class NotificationTrigger : Trigger<NotificationEvent> {

    override val definition = triggerNode<NotificationEvent>(
        typeId = "trigger.notification",
        displayName = "Notification Received",
        description = "Starts when a matching notification arrives",
        category = NodeCategory.MESSAGING,
        iconKey = "notification",
        dataOutputs = listOf(dataOut<NotificationEvent>("notification")),
        configFields = listOf(
            ConfigField(
                key = "package",
                label = "App package filter (optional)",
                type = ConfigFieldType.STR,
            ),
        ),
        encodeData = { notification -> mapOf("notification" to Item.of(notification)) },
    )

    override fun activate(node: WorkflowNode, host: TriggerHost): Flow<NodeOutput<NotificationEvent>> =
        host.busEvents()
            .filter { it.source == TriggerSource.NOTIFICATION }
            .filter { event ->
                val packageFilter = node.config["package"]?.takeIf { it.isNotBlank() }
                packageFilter == null || event.payload["package"] == packageFilter
            }
            .map { event ->
                NodeOutput(
                    NotificationEvent(
                        packageName = event.payload["package"].orEmpty(),
                        title = event.payload["title"].orEmpty(),
                        text = event.payload["text"].orEmpty(),
                        timestamp = event.payload["timestamp"]?.toLongOrNull() ?: event.firedAtEpochMs,
                    ),
                )
            }
}
