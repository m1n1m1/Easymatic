package io.github.m1n1m1.easymatic.engine.action

import io.github.m1n1m1.easymatic.core.service.LogLevel
import io.github.m1n1m1.easymatic.domain.model.NodeCategory
import io.github.m1n1m1.easymatic.domain.model.NodeIcon
import io.github.m1n1m1.easymatic.domain.model.config.Label
import io.github.m1n1m1.easymatic.domain.model.config.Picker
import io.github.m1n1m1.easymatic.domain.model.config.PickerKind
import io.github.m1n1m1.easymatic.domain.model.config.Wired
import io.github.m1n1m1.easymatic.engine.Action
import io.github.m1n1m1.easymatic.engine.ExecutionContext
import io.github.m1n1m1.easymatic.engine.NodeOutput
import io.github.m1n1m1.easymatic.engine.effectNode
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
 * as a static literal. Pulses `out` either way, and every way of failing says so
 * in the console — including the one Android reports to nobody, which is why this
 * node declares [LAUNCH_OVERLAY_PERMISSION].
 */
class LaunchAppAction : Action<LaunchAppConfig, Unit> {

    override val definition = effectNode<LaunchAppConfig>(
        typeId = "action.launch_app",
        displayName = "Launch App",
        description = "Launches another app by package name",
        category = NodeCategory.APPS,
        icon = NodeIcon.BOLT,
        permissions = listOf(LAUNCH_OVERLAY_PERMISSION),
    )

    override suspend fun execute(input: LaunchAppConfig, context: ExecutionContext): NodeOutput<Unit> {
        // Without this a blank package reaches getLaunchIntentForPackage(""), which
        // reports "not installed" — sending the user to look for an app they never
        // chose. Same guard, and the same reason, as Open URL's "No URL set".
        val packageName = input.packageName.trim()
        if (packageName.isEmpty()) {
            context.log("No app chosen", LogLevel.ERROR)
            return NodeOutput(Unit)
        }
        context.reportLaunch(context.systemServices.launchApp(packageName), packageName)
        return NodeOutput(Unit)
    }
}
