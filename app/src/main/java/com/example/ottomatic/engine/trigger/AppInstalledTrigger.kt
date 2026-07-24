package com.example.ottomatic.engine.trigger

import com.example.ottomatic.core.trigger.TriggerSource
import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.WorkflowNode
import com.example.ottomatic.domain.model.dataOut
import com.example.ottomatic.domain.model.items.PackageEvent
import com.example.ottomatic.domain.model.schema.Item
import com.example.ottomatic.domain.registry.ConfigField
import com.example.ottomatic.domain.registry.ConfigFieldType
import com.example.ottomatic.engine.NodeOutput
import com.example.ottomatic.engine.triggerNode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map

/**
 * Trigger for `trigger.app_installed`. Fires when an app is installed, removed,
 * or replaced. Optionally filters by event type and package name.
 *
 * Produces a typed [PackageEvent] item on the `package` data port.
 */
class AppInstalledTrigger : Trigger<PackageEvent> {

    override val definition = triggerNode<PackageEvent>(
        typeId = "trigger.app_installed",
        displayName = "App Installed / Removed",
        description = "Starts when an app is installed, removed or replaced",
        category = NodeCategory.AUTOMATION,
        iconKey = "bolt",
        dataOutputs = listOf(dataOut<PackageEvent>("package")),
        configFields = listOf(
            ConfigField(
                key = CONFIG_EVENT,
                label = "Action",
                type = ConfigFieldType.ENUM(options = listOf("any", "installed", "removed", "replaced")),
                defaultValue = DEFAULT_EVENT,
            ),
            ConfigField(
                key = CONFIG_PACKAGE,
                label = "Package filter (e.g. com.example.app, optional)",
                type = ConfigFieldType.STR,
            ),
        ),
        encodeData = { event -> mapOf("package" to Item.of(event)) },
    )

    override fun activate(node: WorkflowNode, host: TriggerHost): Flow<NodeOutput<PackageEvent>> =
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
                NodeOutput(
                    PackageEvent(
                        action = event.payload[KEY_EVENT].orEmpty(),
                        packageName = event.payload[KEY_PACKAGE_NAME].orEmpty(),
                        timestamp = event.payload[KEY_TIMESTAMP]?.toLongOrNull() ?: event.firedAtEpochMs,
                    ),
                )
            }

    companion object {
        const val CONFIG_PACKAGE = "package"
    }
}
