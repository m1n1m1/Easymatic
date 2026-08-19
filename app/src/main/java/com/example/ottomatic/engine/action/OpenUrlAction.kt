package com.example.ottomatic.engine.action

import com.example.ottomatic.core.service.LogLevel
import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.NodeIcon
import com.example.ottomatic.domain.model.WebUrl
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
 * literal.
 *
 * The **scheme is optional** — `google.com` opens exactly as
 * `https://google.com` does, through [WebUrl.normalize]. Pulses `out` in every
 * case; text that is not a URL and a URL nothing can handle are two different
 * log lines, because they send the user to two different places.
 */
class OpenUrlAction : Action<OpenUrlConfig, Unit> {

    override val definition = effectNode<OpenUrlConfig>(
        typeId = "action.open_url",
        displayName = "Open URL",
        description = "Opens a URL in the default handler (browser or app). Typing google.com is enough",
        category = NodeCategory.APPS,
        icon = NodeIcon.BOLT,
        permissions = listOf(LAUNCH_OVERLAY_PERMISSION),
    )

    override suspend fun execute(input: OpenUrlConfig, context: ExecutionContext): NodeOutput<Unit> {
        val url = WebUrl.normalize(input.url)
        if (url == null) {
            val typed = input.url.trim()
            context.log(
                if (typed.isEmpty()) "No URL set" else "Not a URL: \"$typed\"",
                LogLevel.ERROR,
            )
            return NodeOutput(Unit)
        }
        context.reportLaunch(context.systemServices.openUrl(url), url)
        return NodeOutput(Unit)
    }
}
