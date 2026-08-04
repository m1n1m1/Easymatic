package com.example.ottomatic.engine.action

import com.example.ottomatic.core.service.LogLevel
import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.NodeIcon
import com.example.ottomatic.domain.model.config.Label
import com.example.ottomatic.domain.model.config.Picker
import com.example.ottomatic.domain.model.config.PickerKind
import com.example.ottomatic.domain.model.config.Wired
import com.example.ottomatic.engine.Action
import com.example.ottomatic.engine.ExecutionContext
import com.example.ottomatic.engine.NodeOutput
import com.example.ottomatic.engine.effectNode
import kotlinx.serialization.Serializable

/**
 * Config for `action.launch_app`.
 *
 * The app is chosen from the installed ones rather than typed — nobody knows
 * `com.google.android.apps.maps` by heart, and a typo produced a node that simply
 * logged a failure. It stays `@Wired` all the same: the port is hidden until the
 * socket beside the field is switched on, and launching an app whose package a
 * script worked out is a real capability nothing else in the palette can express.
 */
@Serializable
data class LaunchAppConfig(
    @Label("App") @Picker(PickerKind.APP) @Wired val packageName: String = "",
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
