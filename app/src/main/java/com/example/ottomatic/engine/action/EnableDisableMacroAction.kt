package com.example.ottomatic.engine.action

import com.example.ottomatic.domain.model.items.MacroControlState
import com.example.ottomatic.domain.model.schema.Item
import com.example.ottomatic.engine.Action
import com.example.ottomatic.engine.ActionInput
import com.example.ottomatic.engine.ActionResult
import com.example.ottomatic.engine.ExecutionContext

/**
 * Action for `action.enable_macro`. Enables another macro by id — persists the
 * `enabled` flag and arms its triggers via [com.example.ottomatic.core.service.MacroControl].
 *
 * `macroId` may be wired from upstream data or set as a static literal. The
 * resulting [MacroControlState] on the `state` data port reports whether the
 * request was dispatched.
 */
class EnableMacroAction : AbstractMacroAction() {

    override val typeId: String = TYPE_ID

    override fun control(macroControl: com.example.ottomatic.core.service.MacroControl, macroId: String): Boolean =
        macroControl.enable(macroId)

    companion object {
        const val TYPE_ID = "action.enable_macro"
    }
}

/**
 * Action for `action.disable_macro`. Disables another macro by id — persists
 * `enabled=false` and disarms its triggers. Mirror of [EnableMacroAction].
 */
class DisableMacroAction : AbstractMacroAction() {

    override val typeId: String = TYPE_ID

    override fun control(macroControl: com.example.ottomatic.core.service.MacroControl, macroId: String): Boolean =
        macroControl.disable(macroId)

    companion object {
        const val TYPE_ID = "action.disable_macro"
    }
}

/**
 * Shared behaviour for [EnableMacroAction] and [DisableMacroAction]. Reads the
 * `macroId` input (wired or static), dispatches via [control], and reports
 * the result as a [MacroControlState] on its `state` data port. When
 * [ExecutionContext.macroControl] is unavailable (engine-only tests),
 * [MacroControlState.changed] is `false`.
 */
abstract class AbstractMacroAction : Action {

    abstract fun control(macroControl: com.example.ottomatic.core.service.MacroControl, macroId: String): Boolean

    override suspend fun execute(input: ActionInput, context: ExecutionContext): ActionResult {
        val macroId = input.string("macroId", default = "")
        val macroControl = context.macroControl
        val changed = macroControl?.let { control(it, macroId) } ?: false
        val state = MacroControlState(macroId = macroId, changed = changed)
        return ActionResult(
            execOut = listOf("out"),
            dataOut = mapOf("state" to Item.of(state)),
        )
    }
}
