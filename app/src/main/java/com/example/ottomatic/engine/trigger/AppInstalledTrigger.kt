package com.example.ottomatic.engine.trigger

import com.example.ottomatic.core.trigger.TriggerSource
import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.NodeIcon
import com.example.ottomatic.domain.model.WorkflowNode
import com.example.ottomatic.domain.model.config.Label
import com.example.ottomatic.domain.model.dataOut
import com.example.ottomatic.domain.model.items.PackageEvent
import com.example.ottomatic.engine.NodeOutput
import com.example.ottomatic.engine.triggerNode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable

/** The package transitions `trigger.app_installed` can filter on. */
@Serializable
enum class PackageAction {
    INSTALLED,
    REMOVED,
    REPLACED,
}

/** Config for `trigger.app_installed`; an unset action or package matches all. */
@Serializable
data class AppInstalledConfig(
    @Label("Action") val action: PackageAction? = null,
    @Label("Package filter (e.g. com.example.app, optional)") val packageFilter: String = "",
)

/**
 * Trigger for `trigger.app_installed`. Fires when an app is installed, removed,
 * or replaced. Optionally filters by action and package name.
 *
 * Produces a typed [PackageEvent] item on the `package` data port.
 */
class AppInstalledTrigger : Trigger<AppInstalledConfig, PackageEvent> {

    override val definition = triggerNode<AppInstalledConfig, PackageEvent>(
        typeId = "trigger.app_installed",
        displayName = "App Installed / Removed",
        description = "Starts when an app is installed, removed or replaced",
        category = NodeCategory.AUTOMATION,
        icon = NodeIcon.BOLT,
        output = dataOut<PackageEvent>("package", label = "Package"),
    )

    override fun activate(
        config: AppInstalledConfig,
        node: WorkflowNode,
        host: TriggerHost,
    ): Flow<NodeOutput<PackageEvent>> {
        val wantedAction = config.action?.payloadValue
        val wantedPackage = config.packageFilter.takeIf { it.isNotBlank() }
        return host.busEvents()
            .filter { it.source == TriggerSource.PACKAGE }
            .filter { it.payload[KEY_TRIGGER_TYPE] == TRIGGER_TYPE }
            .filter { wantedAction == null || wantedAction == it.payload[KEY_EVENT] }
            .filter { wantedPackage == null || wantedPackage == it.payload[KEY_PACKAGE_NAME] }
            .map { event ->
                NodeOutput(
                    PackageEvent(
                        action = event.payload[KEY_EVENT].orEmpty(),
                        packageName = event.payload[KEY_PACKAGE_NAME].orEmpty(),
                        timestamp = event.payload[KEY_TIMESTAMP]?.toLongOrNull() ?: event.firedAtEpochMs,
                    ),
                )
            }
    }

    private companion object {
        const val TRIGGER_TYPE = "app_installed"
    }
}
