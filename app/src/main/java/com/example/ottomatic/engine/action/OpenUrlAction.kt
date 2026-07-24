package com.example.ottomatic.engine.action

import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.dataInPort
import com.example.ottomatic.domain.registry.ConfigField
import com.example.ottomatic.domain.registry.ConfigFieldType
import com.example.ottomatic.engine.Action
import com.example.ottomatic.engine.ExecutionContext
import com.example.ottomatic.engine.NodeOutput
import com.example.ottomatic.engine.actionNode

data class OpenUrlInput(val url: String)

/**
 * Action for `action.open_url`. Opens a URL in the default handler (browser or
 * app via intent). `url` may be wired from upstream data or set as a static
 * literal. Pulses `out`; on failure (no handler) still pulses `out` but logs.
 */
class OpenUrlAction : Action<OpenUrlInput, Unit> {

    override val definition = actionNode<OpenUrlInput, Unit>(
        typeId = "action.open_url",
        displayName = "Open URL",
        description = "Opens a URL in the default handler (browser or app)",
        category = NodeCategory.NETWORK,
        iconKey = "bolt",
        dataInputs = listOf(dataInPort<String>("url")),
        configFields = listOf(
            ConfigField(
                key = "url",
                label = "URL",
                type = ConfigFieldType.STR,
                defaultValue = "https://example.com",
            ),
        ),
        decode = { input -> OpenUrlInput(input.text("url")) },
        encodeData = { emptyMap() },
    )

    override suspend fun execute(input: OpenUrlInput, context: ExecutionContext): NodeOutput<Unit> {
        val ok = context.systemServices.openUrl(input.url)
        if (!ok) context.log("Open url failed: ${input.url}")
        return NodeOutput(Unit)
    }
}
