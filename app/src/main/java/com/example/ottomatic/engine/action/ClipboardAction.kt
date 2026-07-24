package com.example.ottomatic.engine.action

import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.dataInPort
import com.example.ottomatic.domain.registry.ConfigField
import com.example.ottomatic.domain.registry.ConfigFieldType
import com.example.ottomatic.engine.Action
import com.example.ottomatic.engine.ExecutionContext
import com.example.ottomatic.engine.NodeOutput
import com.example.ottomatic.engine.actionNode

data class ClipboardInput(val mode: String, val text: String)

/**
 * Action for `action.clipboard`. Sets the clipboard primary clip to `text`
 * (wired from upstream data or a static literal) or clears it when
 * `mode = "clear"`. Passthrough on exec.
 */
class ClipboardAction : Action<ClipboardInput, Unit> {

    override val definition = actionNode<ClipboardInput, Unit>(
        typeId = "action.clipboard",
        displayName = "Clipboard",
        description = "Sets or clears the clipboard",
        category = NodeCategory.DATA,
        iconKey = "bolt",
        dataInputs = listOf(dataInPort<String>("text")),
        configFields = listOf(
            ConfigField(
                key = "mode",
                label = "Mode",
                type = ConfigFieldType.ENUM(options = listOf("set", "clear")),
                defaultValue = "set",
            ),
            ConfigField(
                key = "text",
                label = "Text",
                type = ConfigFieldType.MULTILINE,
                defaultValue = "",
            ),
        ),
        decode = { input -> ClipboardInput(input.configString("mode", "set"), input.text("text")) },
        encodeData = { emptyMap() },
    )

    override suspend fun execute(input: ClipboardInput, context: ExecutionContext): NodeOutput<Unit> {
        if (input.mode == "clear") {
            context.systemServices.clearClipboard()
        } else {
            context.systemServices.setClipboard(input.text)
        }
        return NodeOutput(Unit)
    }
}
