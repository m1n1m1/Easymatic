package io.github.m1n1m1.easymatic.engine.action

import io.github.m1n1m1.easymatic.core.service.LogLevel
import io.github.m1n1m1.easymatic.domain.model.NodeCategory
import io.github.m1n1m1.easymatic.domain.model.NodeIcon
import io.github.m1n1m1.easymatic.domain.model.config.Label
import io.github.m1n1m1.easymatic.domain.model.config.Wired
import io.github.m1n1m1.easymatic.domain.model.dataOut
import io.github.m1n1m1.easymatic.domain.model.items.NotificationRemoved
import io.github.m1n1m1.easymatic.domain.registry.NOTIFY_CANCEL_TYPE_ID
import io.github.m1n1m1.easymatic.engine.Action
import io.github.m1n1m1.easymatic.engine.ExecutionContext
import io.github.m1n1m1.easymatic.engine.NodeOutput
import io.github.m1n1m1.easymatic.engine.actionNode
import kotlinx.serialization.Serializable

/**
 * Config for `action.notify_cancel`.
 *
 * [tag] is `@Wired` because the tag worth cancelling is usually one a macro worked
 * out rather than one somebody typed — `action.notify`'s own `Tag` output is the
 * obvious source, and it answers even when the field on that node was left blank.
 */
@Serializable
data class NotifyCancelConfig(
    @Label("Tag") @Wired val tag: String = "",
)

/**
 * Action for `action.notify_cancel` — takes down a notification this app posted.
 *
 * The other half of `action.notify`'s tag. Posting under a tag replaces whatever was
 * there, which covers "keep one notification up to date"; this covers the other thing
 * a tag is for, which is being able to stop. Before either existed a notification was
 * unreachable the instant it left — the id was `System.currentTimeMillis()`, so
 * nothing could name it again.
 *
 * **Easymatic's own notifications only.** Dismissing somebody else's is
 * `action.notification_action`, which needs notification access and addresses a
 * conversation rather than a tag. Nothing this node holds is meaningful there.
 *
 * **Never throws.** A blank tag, a tag naming nothing, and notifications being
 * switched off all land on `state` with `changed = false` and `out` still pulses:
 * a notification that is already gone is the outcome that was asked for, not a
 * failure to report.
 */
class NotifyCancelAction : Action<NotifyCancelConfig, NotificationRemoved> {

    override val definition = actionNode<NotifyCancelConfig, NotificationRemoved>(
        typeId = NOTIFY_CANCEL_TYPE_ID.value,
        displayName = "Remove Notification",
        description = "Takes down a notification this app posted under a tag",
        category = NodeCategory.NOTIFICATIONS,
        icon = NodeIcon.NOTIFICATION,
        output = dataOut<NotificationRemoved>("state"),
    )

    override suspend fun execute(
        input: NotifyCancelConfig,
        context: ExecutionContext,
    ): NodeOutput<NotificationRemoved> {
        val tag = input.tag.trim()
        if (tag.isEmpty()) {
            val problem = "No tag given, so there is no notification to remove"
            context.log(problem, LogLevel.WARN)
            return NodeOutput(NotificationRemoved(tag, changed = false, error = problem))
        }
        val removed = context.notifications.cancel(tag)
        context.log(
            if (removed) "Removed the notification tagged '$tag'" else "Nothing was showing under '$tag'",
            if (removed) LogLevel.INFO else LogLevel.DEBUG,
        )
        return NodeOutput(NotificationRemoved(tag, changed = removed))
    }
}
