package com.example.ottomatic.engine.action

import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.dataInPort
import com.example.ottomatic.domain.model.dataOut
import com.example.ottomatic.domain.model.items.MacroControlState
import com.example.ottomatic.domain.model.schema.Item
import com.example.ottomatic.domain.registry.ConfigField
import com.example.ottomatic.domain.registry.ConfigFieldType
import com.example.ottomatic.engine.Action
import com.example.ottomatic.engine.ActionNodeDefinition
import com.example.ottomatic.engine.ExecutionContext
import com.example.ottomatic.engine.NodeOutput
import com.example.ottomatic.engine.actionNode

data class MacroInput(val macroId: String)

/**
 * Action for `action.enable_macro`. Enables another macro by id — persists the
 * `enabled` flag and arms its triggers via [com.example.ottomatic.core.service.MacroControl].
 *
 * `macroId` may be wired from upstream data or set as a static literal. The
 * resulting [MacroControlState] on the `state` data port reports whether the
 * request was dispatched.
 */
class EnableMacroAction : AbstractMacroAction() {

    override val definition = macroDefinition(
        typeId = "action.enable_macro",
        displayName = "Enable Macro",
        description = "Enables another macro by id (persists the flag and arms its triggers)",
    )

    override fun control(macroControl: com.example.ottomatic.core.service.MacroControl, macroId: String): Boolean =
        macroControl.enable(macroId)
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

    override fun control(macroControl: com.example.ottomatic.core.service.MacroControl, macroId: String): Boolean =
        macroControl.disable(macroId)
}

/**
 * Shared behaviour for [EnableMacroAction] and [DisableMacroAction]. Reads the
 * `macroId` input (wired or static), dispatches via [control], and reports
 * the result as a [MacroControlState] on its `state` data port. When
 * [ExecutionContext.macroControl] is unavailable (engine-only tests),
 * [MacroControlState.changed] is `false`.
 */
abstract class AbstractMacroAction : Action<MacroInput, MacroControlState> {

    abstract override val definition: ActionNodeDefinition<MacroInput, MacroControlState>

    abstract fun control(macroControl: com.example.ottomatic.core.service.MacroControl, macroId: String): Boolean

    override suspend fun execute(input: MacroInput, context: ExecutionContext): NodeOutput<MacroControlState> {
        val macroControl = context.macroControl
        val changed = macroControl?.let { control(it, input.macroId) } ?: false
        return NodeOutput(MacroControlState(macroId = input.macroId, changed = changed))
    }
}

private fun macroDefinition(
    typeId: String,
    displayName: String,
    description: String,
): ActionNodeDefinition<MacroInput, MacroControlState> = actionNode(
    typeId = typeId,
    displayName = displayName,
    description = description,
    category = NodeCategory.FLOW_CONTROL,
    iconKey = "bolt",
    dataInputs = listOf(dataInPort<String>("macroId")),
    dataOutputs = listOf(dataOut<MacroControlState>("state")),
    configFields = listOf(
        ConfigField(
            key = "macroId",
            label = "Macro id",
            type = ConfigFieldType.STR,
            defaultValue = "",
        ),
    ),
    decode = { input -> MacroInput(input.text("macroId")) },
    encodeData = { state -> mapOf("state" to Item.of(state)) },
)
