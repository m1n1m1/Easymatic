package com.example.ottomatic.engine.action

import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.dataInPort
import com.example.ottomatic.domain.registry.ConfigField
import com.example.ottomatic.domain.registry.ConfigFieldType
import com.example.ottomatic.engine.Action
import com.example.ottomatic.engine.ExecutionContext
import com.example.ottomatic.engine.NodeOutput
import com.example.ottomatic.engine.actionNode

data class NotifyInput(val title: String, val text: String)

/**
 * Action for `action.notify`. Posts a device notification with title/text.
 * `text` may be wired from an upstream data edge or set as a static literal.
 */
class NotifyAction : Action<NotifyInput, Unit> {

    override val definition = actionNode<NotifyInput, Unit>(
        typeId = "action.notify",
        displayName = "Show Notification",
        description = "Posts a notification on this device",
        category = NodeCategory.NOTIFICATIONS,
        iconKey = "send",
        dataInputs = listOf(dataInPort<String>("text")),
        configFields = listOf(
            ConfigField(
                key = "title",
                label = "Title",
                type = ConfigFieldType.STR,
                defaultValue = "Ottomatic",
            ),
            ConfigField(
                key = "text",
                label = "Text",
                type = ConfigFieldType.MULTILINE,
                defaultValue = "Workflow ran",
            ),
        ),
        decode = { input ->
            NotifyInput(input.configString("title", "Ottomatic"), input.text("text", "Workflow ran"))
        },
        encodeData = { emptyMap() },
    )

    override suspend fun execute(input: NotifyInput, context: ExecutionContext): NodeOutput<Unit> {
        context.systemServices.notify(input.title, input.text)
        return NodeOutput(Unit)
    }
}
