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

/** Config for `action.notify`. */
@Serializable
data class NotifyConfig(
    @Label("Title") val title: String = "Ottomatic",
    @Label("Text") @Multiline @Wired val text: String = "Workflow ran",
)

/**
 * Action for `action.notify`. Posts a device notification with title/text.
 * `text` may be wired from an upstream data edge or set as a static literal.
 */
class NotifyAction : Action<NotifyConfig, Unit> {

    override val definition = effectNode<NotifyConfig>(
        typeId = "action.notify",
        displayName = "Show Notification",
        description = "Posts a notification on this device",
        category = NodeCategory.NOTIFICATIONS,
        icon = NodeIcon.SEND,
    )

    override suspend fun execute(input: NotifyConfig, context: ExecutionContext): NodeOutput<Unit> {
        context.systemServices.notify(input.title, input.text)
        return NodeOutput(Unit)
    }
}
