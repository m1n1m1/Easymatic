package com.example.ottomatic.engine.trigger

import com.example.ottomatic.core.trigger.TriggerSource
import com.example.ottomatic.domain.model.WorkflowNode
import com.example.ottomatic.domain.model.items.PackageEvent
import com.example.ottomatic.domain.model.schema.Item
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map

/**
 * Trigger for `trigger.app_installed`. Fires when an app is installed, removed,
 * or replaced. Optionally filters by event type and package name.
 *
 * Produces a typed [PackageEvent] item on the `package` data port.
 */
class AppInstalledTrigger : Trigger {

    override val typeId: String = TYPE_ID

    override fun activate(node: WorkflowNode, host: TriggerHost): Flow<TriggerEvent> =
        host.busEvents()
            .filter { it.source == TriggerSource.PACKAGE }
            .filter { it.payload[KEY_TRIGGER_TYPE] == "app_installed" }
            .filter { event ->
                val eventFilter = node.config[CONFIG_EVENT]?.takeIf { it.isNotBlank() } ?: DEFAULT_EVENT
                eventFilter == DEFAULT_EVENT || eventFilter == event.payload[KEY_EVENT]
            }
            .filter { event ->
                val pkgFilter = node.config[CONFIG_PACKAGE]?.takeIf { it.isNotBlank() }
                pkgFilter == null || event.payload[KEY_PACKAGE_NAME] == pkgFilter
            }
            .map { event ->
                val packageEvent = PackageEvent(
                    action = event.payload[KEY_EVENT].orEmpty(),
                    packageName = event.payload[KEY_PACKAGE_NAME].orEmpty(),
                    timestamp = event.payload[KEY_TIMESTAMP]?.toLongOrNull() ?: event.firedAtEpochMs,
                )
                TriggerEvent(
                    triggerNodeId = node.id,
                    dataOut = mapOf("package" to Item.of(packageEvent)),
                )
            }

    companion object {
        const val TYPE_ID = "trigger.app_installed"
        const val CONFIG_PACKAGE = "package"
    }
}
