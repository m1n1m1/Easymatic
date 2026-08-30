package io.github.m1n1m1.easymatic.engine.action

import io.github.m1n1m1.easymatic.core.service.MacroControl
import io.github.m1n1m1.easymatic.domain.model.NodeCategory
import io.github.m1n1m1.easymatic.domain.model.NodeIcon
import io.github.m1n1m1.easymatic.domain.model.config.Label
import io.github.m1n1m1.easymatic.domain.model.config.Picker
import io.github.m1n1m1.easymatic.domain.model.config.PickerKind
import io.github.m1n1m1.easymatic.domain.model.config.Wired
import io.github.m1n1m1.easymatic.domain.model.dataOut
import io.github.m1n1m1.easymatic.domain.model.items.MacroControlState
import io.github.m1n1m1.easymatic.engine.Action
import io.github.m1n1m1.easymatic.engine.ActionNodeDefinition
import io.github.m1n1m1.easymatic.engine.ExecutionContext
import io.github.m1n1m1.easymatic.engine.NodeOutput
import io.github.m1n1m1.easymatic.engine.actionNode
import kotlinx.serialization.Serializable

/**
 * Config shared by `action.enable_macro` and `action.disable_macro`.
 *
 * A macro id is a UUID, so it is chosen rather than typed: before the picker, this
 * field asked the user for a value nobody can produce and nothing could check, and a
 * wrong one looked exactly like a right one until the node quietly did nothing. It
 * stays `@Wired` for the same reason `action.launch_app`'s package does — the port
 * only appears once its socket is switched on, and an existing graph may feed it.
 */
@Serializable
data class MacroConfig(
    @Label("Macro") @Picker(PickerKind.MACRO) @Wired val macroId: String = "",
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
