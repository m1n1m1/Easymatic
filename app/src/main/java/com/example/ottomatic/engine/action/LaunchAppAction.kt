package com.example.ottomatic.engine.action

import com.example.ottomatic.engine.Action
import com.example.ottomatic.engine.ActionInput
import com.example.ottomatic.engine.ActionResult
import com.example.ottomatic.engine.ExecutionContext

/**
 * Action for `action.launch_app`. Launches another app by its package name via
 * its main launcher activity. `package` (EXPR) is interpolated against the
 * runtime data context so it can be selected dynamically from upstream data.
 * Pulses `out` on success; on failure (package not installed / no launcher
 * activity) still pulses `out` but logs the failure.
 */
class LaunchAppAction : Action {

    override val typeId: String = TYPE_ID

    override suspend fun execute(input: ActionInput, context: ExecutionContext): ActionResult {
        val packageName = input.config.expr("package", default = "")
        val ok = context.systemServices.launchApp(packageName)
        if (!ok) context.log("Launch app failed: $packageName")
        return ActionResult.passthrough("out")
    }

    companion object {
        const val TYPE_ID = "action.launch_app"
    }
}
