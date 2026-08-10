package com.example.ottomatic.engine.action

import com.example.ottomatic.core.permissions.PermissionRequirement
import com.example.ottomatic.core.permissions.PrerequisiteType
import com.example.ottomatic.core.service.LogLevel
import com.example.ottomatic.core.service.NotificationAct
import com.example.ottomatic.core.service.NotificationOp
import com.example.ottomatic.domain.model.ConversationRef
import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.NodeIcon
import com.example.ottomatic.domain.model.config.Label
import com.example.ottomatic.domain.model.config.VisibleWhen
import com.example.ottomatic.domain.model.config.Wired
import com.example.ottomatic.domain.model.dataOut
import com.example.ottomatic.domain.model.items.NotificationActed
import com.example.ottomatic.engine.Action
import com.example.ottomatic.engine.ExecutionContext
import com.example.ottomatic.engine.NodeOutput
import com.example.ottomatic.engine.actionNode
import kotlinx.serialization.Serializable

/**
 * Config for `action.notification_action`.
 *
 * [conversationId] takes `trigger.message`'s port of the same name, wired in exactly
 * as `action.reply_message` takes it and named identically for the same reason.
 * [label] is only asked for by [NotificationOp.RUN_ACTION], which is what
 * `@VisibleWhen` is for — the shape `action.mail_update`'s "Move to folder" already
 * has.
 */
@Serializable
data class NotificationActionConfig(
    @Label("Conversation ID") @Wired val conversationId: String = "",
    @Label("What to do") val op: NotificationOp = NotificationOp.MARK_READ,
    @Label("Button") @VisibleWhen("op", "RUN_ACTION") @Wired val label: String = "",
)

/**
 * Action for `action.notification_action`. Marks a conversation read, presses one of
 * the notification's own buttons, or dismisses it.
 *
 * **One node rather than three**, on `action.mail_update`'s argument: these take the
 * same input, produce the same output and all pulse the single `out`, and what
 * differs between them is one enum. Three palette rows for one idea would be the
 * wrong trade.
 *
 * This is the node that makes an auto-reply liveable. A macro that answers a message
 * and leaves the notification sitting there means the phone keeps buzzing about a
 * chat that has already been dealt with; marking it read is what closes the loop.
 *
 * `MARK_READ` finds the right button by **meaning rather than by its text** — Android
 * has a semantic action for it, so this works on a phone set to any language.
 * `RUN_ACTION` is the escape hatch for everything with no such constant ("Mute",
 * "Like", "Snooze"), and there the label is all there is to go on, so it is matched
 * loosely and a miss reports which buttons were actually there.
 *
 * **Never throws.** A reference naming nothing, a notification already gone, a button
 * the app does not offer and notification access being switched off all land on
 * `state` with `changed = false` and the reason in `error`, and `out` still pulses.
 */
class NotificationActionAction : Action<NotificationActionConfig, NotificationActed> {

    override val definition = actionNode<NotificationActionConfig, NotificationActed>(
        typeId = "action.notification_action",
        displayName = "Act on Notification",
        description = "Marks a conversation read, presses one of its buttons, or dismisses the notification",
        category = NodeCategory.NOTIFICATIONS,
        icon = NodeIcon.NOTIFICATION,
        output = dataOut<NotificationActed>("state"),
        permissions = listOf(
            PermissionRequirement(
                manifestPermission = null,
                type = PrerequisiteType.NOTIFICATION_LISTENER,
                rationaleKey = "notification.listener",
            ),
        ),
    )

    override suspend fun execute(
        input: NotificationActionConfig,
        context: ExecutionContext,
    ): NodeOutput<NotificationActed> {
        val parsed = ConversationRef.parse(input.conversationId)
        val problem = when {
            input.conversationId.isBlank() -> "No conversation wired in"
            parsed == null -> "Not a conversation id: \"${input.conversationId.trim()}\""
            input.op == NotificationOp.RUN_ACTION && input.label.isBlank() ->
                "No button named, so there is nothing to press"
            else -> null
        }
        if (parsed == null || problem != null) {
            context.log(problem.orEmpty(), LogLevel.ERROR)
            return NodeOutput(
                NotificationActed(input.conversationId, input.op.name, changed = false, error = problem.orEmpty()),
            )
        }
        val result = context.messaging.act(
            NotificationAct(
                packageName = parsed.packageName,
                key = parsed.key,
                op = input.op,
                label = input.label,
            ),
        )
        if (!result.done) context.log(result.error, LogLevel.WARN)
        return NodeOutput(NotificationActed(input.conversationId, input.op.name, result.done, result.error))
    }
}
