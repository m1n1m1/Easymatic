package com.example.ottomatic.engine.action

import com.example.ottomatic.core.service.MacroControl
import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.NodeIcon
import com.example.ottomatic.domain.model.config.Label
import com.example.ottomatic.domain.model.config.Wired
import com.example.ottomatic.domain.model.dataOut
import com.example.ottomatic.domain.model.items.MacroControlState
import com.example.ottomatic.engine.Action
import com.example.ottomatic.engine.ActionNodeDefinition
import com.example.ottomatic.engine.ExecutionContext
import com.example.ottomatic.engine.NodeOutput
import com.example.ottomatic.engine.actionNode
import kotlinx.serialization.Serializable

/** Config shared by `action.enable_macro` and `action.disable_macro`. */
@Serializable
data class MacroConfig(
    @Label("Macro id") @Wired val macroId: String = "",
)

/**
 * Action for `action.enable_macro`. Enables another macro by id — persists the
 * `enabled` flag and arms its triggers via [MacroControl].
 */
class EnableMacroAction : AbstractMacroAction() {

    override val definition = macroDefinition(
        typeId = "action.enable_macro",
        displayName = "Enable Macro",
        description = "Enables another macro by id (persists the flag and arms its triggers)",
    )

    override fun control(macroControl: MacroControl, macroId: String): Boolean = macroControl.enable(macroId)
}

/**
 * Action for `action.disable_macro`. Disables another macro by id — persists
 * `enabled=false` and disarms its triggers. Mirror of [EnableMacroAction].
 */
class DisableMacroAction : AbstractMacroAction() {

    override val definition = macroDefinition(
        typeId = "action.disable_macro",
        displayName = "Disable Macro",
        description = "Disables another macro by id (persists the flag and disarms its triggers)",
    )

    override fun control(macroControl: MacroControl, macroId: String): Boolean = macroControl.disable(macroId)
}

/**
 * Shared behaviour for [EnableMacroAction] and [DisableMacroAction]: dispatches
 * via [control] and reports the result as a [MacroControlState] on its `state`
 * data port. When [ExecutionContext.macroControl] is unavailable (engine-only
 * tests), [MacroControlState.changed] is `false`.
 */
abstract class AbstractMacroAction : Action<MacroConfig, MacroControlState> {

    abstract override val definition: ActionNodeDefinition<MacroConfig, MacroControlState>

    abstract fun control(macroControl: MacroControl, macroId: String): Boolean

    override suspend fun execute(input: MacroConfig, context: ExecutionContext): NodeOutput<MacroControlState> {
        val changed = context.macroControl?.let { control(it, input.macroId) } ?: false
        return NodeOutput(MacroControlState(macroId = input.macroId, changed = changed))
    }
}

private fun macroDefinition(
    typeId: String,
    displayName: String,
    description: String,
): ActionNodeDefinition<MacroConfig, MacroControlState> = actionNode(
    typeId = typeId,
    displayName = displayName,
    description = description,
    category = NodeCategory.FLOW_CONTROL,
    icon = NodeIcon.BOLT,
    output = dataOut<MacroControlState>("state"),
)
