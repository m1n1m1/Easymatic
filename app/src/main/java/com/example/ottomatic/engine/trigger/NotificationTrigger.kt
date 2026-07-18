package com.example.ottomatic.engine.trigger

import com.example.ottomatic.core.trigger.TriggerSource
import com.example.ottomatic.domain.model.WorkflowNode
import com.example.ottomatic.domain.model.items.NotificationEvent
import com.example.ottomatic.domain.model.schema.Item
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map

/**
 * Trigger for `trigger.notification`. Listens to the bus for notification
 * events and optionally filters by app package.
 *
 * Produces a typed [NotificationEvent] item on the `notification` data port.
 */
class NotificationTrigger : Trigger {

    override val typeId: String = TYPE_ID

    override fun activate(node: WorkflowNode, host: TriggerHost): Flow<TriggerEvent> =
        host.busEvents()
            .filter { it.source == TriggerSource.NOTIFICATION }
            .filter { event ->
                val packageFilter = node.config["package"]?.takeIf { it.isNotBlank() }
                packageFilter == null || event.payload["package"] == packageFilter
            }
            .map { event ->
                val notification = NotificationEvent(
                    packageName = event.payload["package"].orEmpty(),
                    title = event.payload["title"].orEmpty(),
                    text = event.payload["text"].orEmpty(),
                    timestamp = event.payload["timestamp"]?.toLongOrNull() ?: event.firedAtEpochMs,
                )
                TriggerEvent(
                    triggerNodeId = node.id,
                    dataOut = mapOf("notification" to Item.of(notification)),
                )
            }

    companion object {
        const val TYPE_ID = "trigger.notification"
    }
}
