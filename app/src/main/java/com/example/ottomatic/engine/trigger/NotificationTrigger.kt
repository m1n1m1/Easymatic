package com.example.ottomatic.engine.trigger

import com.example.ottomatic.core.trigger.TriggerSource
import com.example.ottomatic.domain.model.WorkflowNode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map

/**
 * Trigger for `trigger.notification`. Listens to the bus for notification
 * events and optionally filters by app package.
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
                TriggerEvent(
                    triggerNodeId = node.id,
                    payload = event.payload,
                )
            }

    companion object {
        const val TYPE_ID = "trigger.notification"
    }
}
