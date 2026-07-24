package com.example.ottomatic.engine.action

import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.NodeIcon
import com.example.ottomatic.domain.model.config.Label
import com.example.ottomatic.domain.model.config.Wired
import com.example.ottomatic.engine.Action
import com.example.ottomatic.engine.ExecutionContext
import com.example.ottomatic.engine.NodeOutput
import com.example.ottomatic.engine.effectNode
import kotlinx.serialization.Serializable

/** Config for `action.open_url`. */
@Serializable
data class OpenUrlConfig(
    @Label("URL") @Wired val url: String = "https://example.com",
)

/**
 * Action for `action.open_url`. Opens a URL in the default handler (browser or
 * app via intent). The URL may be wired from upstream data or set as a static
 * literal. Pulses `out` either way; a missing handler is logged.
 */
class OpenUrlAction : Action<OpenUrlConfig, Unit> {

    override val definition = effectNode<OpenUrlConfig>(
        typeId = "action.open_url",
        displayName = "Open URL",
        description = "Opens a URL in the default handler (browser or app)",
        category = NodeCategory.NETWORK,
        icon = NodeIcon.BOLT,
    )

    override suspend fun execute(input: OpenUrlConfig, context: ExecutionContext): NodeOutput<Unit> {
        val ok = context.systemServices.openUrl(input.url)
        if (!ok) context.log("Open url failed: ${input.url}")
        return NodeOutput(Unit)
    }
}
