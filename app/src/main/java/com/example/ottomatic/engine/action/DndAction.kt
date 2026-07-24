package com.example.ottomatic.engine.action

import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.dataOut
import com.example.ottomatic.domain.model.items.DndState
import com.example.ottomatic.domain.model.schema.Item
import com.example.ottomatic.domain.registry.ConfigField
import com.example.ottomatic.domain.registry.ConfigFieldType
import com.example.ottomatic.engine.Action
import com.example.ottomatic.engine.ExecutionContext
import com.example.ottomatic.engine.NodeOutput
import com.example.ottomatic.engine.actionNode

data class DndInput(val enabled: Boolean, val level: String)

/**
 * Action for `action.dnd`. Toggles the Do-Not-Disturb mode and reports the
 * resulting [DndState] on its `state` data port.
 *
 * - `state` (ENUM): `"on"` or `"off"` (default `"on"`).
 * - `level` (ENUM): `"priority"`, `"alarms"`, `"silence"` (default
 *   `"priority"`) — only used when `state = "on"`.
 *
 * Requires the `ACCESS_NOTIFICATION_POLICY` permission granted by the user;
 * when missing, [DndState.changed] is `false`.
 */
class DndAction : Action<DndInput, DndState> {

    override val definition = actionNode<DndInput, DndState>(
        typeId = "action.dnd",
        displayName = "Do Not Disturb",
        description = "Toggles Do-Not-Disturb on or off with a chosen policy level",
        category = NodeCategory.DEVICE_SETTINGS,
        iconKey = "dnd",
        dataOutputs = listOf(dataOut<DndState>("state")),
        configFields = listOf(
            ConfigField(
                key = "state",
                label = "State",
                type = ConfigFieldType.ENUM(options = listOf("on", "off")),
                defaultValue = "on",
            ),
            ConfigField(
                key = "level",
                label = "Level (when on)",
                type = ConfigFieldType.ENUM(options = listOf("priority", "alarms", "silence")),
                defaultValue = "priority",
            ),
        ),
        decode = { input ->
            DndInput(input.configString("state", "on") != "off", input.configString("level", "priority"))
        },
        encodeData = { state -> mapOf("state" to Item.of(state)) },
    )

    override suspend fun execute(input: DndInput, context: ExecutionContext): NodeOutput<DndState> {
        val result = context.systemServices.setDnd(input.enabled, input.level)
        val state = DndState(
            enabled = result?.enabled ?: input.enabled,
            level = result?.level ?: if (input.enabled) input.level else "all",
            changed = result?.changed ?: false,
        )
        return NodeOutput(state)
    }
}
