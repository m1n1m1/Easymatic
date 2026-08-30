package io.github.m1n1m1.easymatic.engine.trigger

import io.github.m1n1m1.easymatic.core.trigger.TriggerSource
import io.github.m1n1m1.easymatic.domain.model.NodeCategory
import io.github.m1n1m1.easymatic.domain.model.NodeIcon
import io.github.m1n1m1.easymatic.domain.model.WorkflowNode
import io.github.m1n1m1.easymatic.domain.model.config.Label
import io.github.m1n1m1.easymatic.domain.model.config.Picker
import io.github.m1n1m1.easymatic.domain.model.config.PickerKind
import io.github.m1n1m1.easymatic.domain.model.dataOut
import io.github.m1n1m1.easymatic.domain.model.items.PackageEvent
import io.github.m1n1m1.easymatic.engine.NodeOutput
import io.github.m1n1m1.easymatic.engine.triggerNode
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
    @Label("App (optional)") @Picker(PickerKind.APP_FILTER) val packageFilter: String = "",
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
                        timestamp = event.timestamp,
                    ),
                )
            }
    }

    private companion object {
        const val TRIGGER_TYPE = "app_installed"
    }
}
