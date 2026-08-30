package io.github.m1n1m1.easymatic.engine.action

import io.github.m1n1m1.easymatic.domain.model.NodeCategory
import io.github.m1n1m1.easymatic.domain.model.NodeIcon
import io.github.m1n1m1.easymatic.domain.model.config.Label
import io.github.m1n1m1.easymatic.domain.model.config.Multiline
import io.github.m1n1m1.easymatic.domain.model.config.Wired
import io.github.m1n1m1.easymatic.engine.Action
import io.github.m1n1m1.easymatic.engine.ExecutionContext
import io.github.m1n1m1.easymatic.engine.NodeOutput
import io.github.m1n1m1.easymatic.engine.effectNode
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
