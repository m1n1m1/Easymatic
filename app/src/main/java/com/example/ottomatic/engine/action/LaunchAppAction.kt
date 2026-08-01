package com.example.ottomatic.engine.action

import com.example.ottomatic.core.service.LogLevel
import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.NodeIcon
import com.example.ottomatic.domain.model.config.Label
import com.example.ottomatic.domain.model.config.Wired
import com.example.ottomatic.engine.Action
import com.example.ottomatic.engine.ExecutionContext
import com.example.ottomatic.engine.NodeOutput
import com.example.ottomatic.engine.effectNode
import kotlinx.serialization.Serializable

/** Config for `action.launch_app`. */
@Serializable
data class LaunchAppConfig(
    @Label("Package name") @Wired val packageName: String = "",
)

/**
 * Action for `action.launch_app`. Launches another app by its package name via
 * its main launcher activity. The package may be wired from upstream data or set
 * as a static literal. Pulses `out` either way; failures (package not installed
 * / no launcher activity) are logged.
 */
class LaunchAppAction : Action<LaunchAppConfig, Unit> {

    override val definition = effectNode<LaunchAppConfig>(
        typeId = "action.launch_app",
        displayName = "Launch App",
        description = "Launches another app by package name",
        category = NodeCategory.NETWORK,
        icon = NodeIcon.BOLT,
    )

    override suspend fun execute(input: LaunchAppConfig, context: ExecutionContext): NodeOutput<Unit> {
        val ok = context.systemServices.launchApp(input.packageName)
        if (!ok) context.log("Launch app failed: ${input.packageName}", LogLevel.ERROR)
        return NodeOutput(Unit)
    }
}
