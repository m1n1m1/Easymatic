package com.example.ottomatic.engine.action

import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.dataInPort
import com.example.ottomatic.domain.registry.ConfigField
import com.example.ottomatic.domain.registry.ConfigFieldType
import com.example.ottomatic.engine.Action
import com.example.ottomatic.engine.ExecutionContext
import com.example.ottomatic.engine.NodeOutput
import com.example.ottomatic.engine.actionNode

data class LaunchAppInput(val packageName: String)

/**
 * Action for `action.launch_app`. Launches another app by its package name via
 * its main launcher activity. `package` may be wired from upstream data or set
 * as a static literal. Pulses `out` on success; on failure (package not
 * installed / no launcher activity) still pulses `out` but logs the failure.
 */
class LaunchAppAction : Action<LaunchAppInput, Unit> {

    override val definition = actionNode<LaunchAppInput, Unit>(
        typeId = "action.launch_app",
        displayName = "Launch App",
        description = "Launches another app by package name",
        category = NodeCategory.NETWORK,
        iconKey = "bolt",
        dataInputs = listOf(dataInPort<String>("package")),
        configFields = listOf(
            ConfigField(
                key = "package",
                label = "Package name",
                type = ConfigFieldType.STR,
                defaultValue = "",
            ),
        ),
        decode = { input -> LaunchAppInput(input.text("package")) },
        encodeData = { emptyMap() },
    )

    override suspend fun execute(input: LaunchAppInput, context: ExecutionContext): NodeOutput<Unit> {
        val ok = context.systemServices.launchApp(input.packageName)
        if (!ok) context.log("Launch app failed: ${input.packageName}")
        return NodeOutput(Unit)
    }
}
