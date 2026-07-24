package com.example.ottomatic.engine.action

import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.NodeIcon
import com.example.ottomatic.domain.model.config.Label
import com.example.ottomatic.domain.model.config.Multiline
import com.example.ottomatic.domain.model.config.Wired
import com.example.ottomatic.engine.Action
import com.example.ottomatic.engine.ExecutionContext
import com.example.ottomatic.engine.NodeOutput
import com.example.ottomatic.engine.effectNode
import kotlinx.serialization.Serializable

/** What `action.clipboard` should do. */
@Serializable
enum class ClipboardMode {
    SET,
    CLEAR,
}

/** Config for `action.clipboard`. */
@Serializable
data class ClipboardConfig(
    @Label("Mode") val mode: ClipboardMode = ClipboardMode.SET,
    @Label("Text") @Multiline @Wired val text: String = "",
)

/**
 * Action for `action.clipboard`. Sets the clipboard primary clip to `text`
 * (wired from upstream data or typed as a literal) or clears it when the mode
 * is [ClipboardMode.CLEAR]. Passthrough on exec.
 */
class ClipboardAction : Action<ClipboardConfig, Unit> {

    override val definition = effectNode<ClipboardConfig>(
        typeId = "action.clipboard",
        displayName = "Clipboard",
        description = "Sets or clears the clipboard",
        category = NodeCategory.DATA,
        icon = NodeIcon.BOLT,
    )

    override suspend fun execute(input: ClipboardConfig, context: ExecutionContext): NodeOutput<Unit> {
        when (input.mode) {
            ClipboardMode.SET -> context.systemServices.setClipboard(input.text)
            ClipboardMode.CLEAR -> context.systemServices.clearClipboard()
        }
        return NodeOutput(Unit)
    }
}
