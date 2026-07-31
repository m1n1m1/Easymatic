package com.example.ottomatic.engine.trigger

import com.example.ottomatic.core.permissions.PermissionRequirement
import com.example.ottomatic.core.permissions.PrerequisiteType
import com.example.ottomatic.core.trigger.TriggerSource
import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.NodeIcon
import com.example.ottomatic.domain.model.WorkflowNode
import com.example.ottomatic.domain.model.config.Label
import com.example.ottomatic.domain.model.dataOut
import com.example.ottomatic.domain.model.items.NotificationEvent
import com.example.ottomatic.engine.NodeOutput
import com.example.ottomatic.engine.triggerNode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable

/** Config for `trigger.notification`; an empty filter matches every app. */
@Serializable
data class NotificationConfig(
    @Label("App package filter (optional)") val packageFilter: String = "",
)

/**
 * Trigger for `trigger.notification`. Listens to the bus for notification
 * events and optionally filters by app package.
 *
 * Produces a typed [NotificationEvent] item on the `notification` data port.
 */
class NotificationTrigger : Trigger<NotificationConfig, NotificationEvent> {

    override val definition = triggerNode<NotificationConfig, NotificationEvent>(
        typeId = "trigger.notification",
        displayName = "Notification Received",
        description = "Starts when a matching notification arrives",
        category = NodeCategory.MESSAGING,
        icon = NodeIcon.NOTIFICATION,
        output = dataOut<NotificationEvent>("notification", label = "Notification"),
        // Nothing reaches NotificationListener until the user switches on
        // notification access in Settings, and until this was declared the node
        // gave no sign of that — it simply never fired.
        permissions = listOf(
            PermissionRequirement(
                manifestPermission = null,
                type = PrerequisiteType.NOTIFICATION_LISTENER,
                rationaleKey = "notification.listener",
            ),
        ),
    )

    override fun activate(
        config: NotificationConfig,
        node: WorkflowNode,
        host: TriggerHost,
    ): Flow<NodeOutput<NotificationEvent>> {
        val packageFilter = config.packageFilter.takeIf { it.isNotBlank() }
        return host.busEvents()
            .filter { it.source == TriggerSource.NOTIFICATION }
            .filter { packageFilter == null || it.payload[KEY_PACKAGE] == packageFilter }
            .map { event ->
                NodeOutput(
                    NotificationEvent(
                        packageName = event.payload[KEY_PACKAGE].orEmpty(),
                        title = event.payload[KEY_TITLE].orEmpty(),
                        text = event.payload[KEY_TEXT].orEmpty(),
                        timestamp = event.timestamp,
                    ),
                )
            }
    }

    companion object {
        const val KEY_PACKAGE = "package"
        const val KEY_TITLE = "title"
        const val KEY_TEXT = "text"
    }
}
