package com.example.ottomatic.engine.action

import com.example.ottomatic.core.service.PromptRequest
import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.NodeIcon
import com.example.ottomatic.domain.model.config.Label
import com.example.ottomatic.domain.model.config.Multiline
import com.example.ottomatic.domain.model.config.Wired
import com.example.ottomatic.domain.registry.DIALOG_MESSAGE_TYPE_ID
import com.example.ottomatic.engine.Action
import com.example.ottomatic.engine.ExecOutputs
import com.example.ottomatic.engine.ExecutionContext
import com.example.ottomatic.engine.ExecutionRoute
import com.example.ottomatic.engine.NodeOutput
import com.example.ottomatic.engine.effectNode
import kotlinx.serialization.Serializable

/**
 * Config for `action.dialog_message`.
 *
 * [timeoutSeconds] is zero by default — a message waits to be acknowledged. It
 * is also what makes the `timed_out` branch appear on the card, so leaving it
 * alone keeps a node with one continuation looking like one.
 */
@Serializable
data class ShowMessageConfig(
    @Label("Title") val title: String = "Ottomatic",
    @Label("Message") @Multiline @Wired val message: String = "",
    @Label("Button") val confirmLabel: String = "OK",
    @Label("Close automatically after (seconds)") val timeoutSeconds: Int = 0,
)

/**
 * `action.dialog_message` — puts a popup on screen and waits until it has been
 * seen.
 *
 * The difference from `action.notify` is the waiting, and it is the whole point:
 * a notification is something the user may find later, this is something the
 * macro will not continue past. "Tell me, then carry on" is a notification;
 * "make sure I have seen this before the next step" is this.
 *
 * Both outcomes lead to `out`, because a message that was read and a message
 * that was dismissed are the same event — there is nothing here to decide. The
 * `timed_out` branch exists for the case where that is not true: a warning that
 * auto-closed after thirty seconds probably did not reach anybody, and a macro
 * may reasonably want to do something else about that.
 */
class ShowMessageAction : Action<ShowMessageConfig, Unit> {

    override val definition = effectNode<ShowMessageConfig>(
        typeId = DIALOG_MESSAGE_TYPE_ID.value,
        displayName = "Show Message",
        description = "Shows a popup dialog and waits until the user has acknowledged it",
        category = NodeCategory.INTERACTION,
        icon = NodeIcon.DIALOG,
        execOutputs = ExecOutputs.ACKNOWLEDGED,
        // Only so `dialogEffectivePorts` is asked whether to draw `timed_out`.
        hasDynamicPorts = true,
        permissions = listOf(OVERLAY_PERMISSION),
    )

    override suspend fun execute(input: ShowMessageConfig, context: ExecutionContext): NodeOutput<Unit> {
        val answer = context.askUser(
            request = PromptRequest(
                title = input.title,
                message = input.message,
                confirmLabel = input.confirmLabel,
                // Nothing to refuse: a message has one button, and back or a tap
                // outside it means the same as pressing that button.
                cancelLabel = null,
            ),
            timeoutSeconds = input.timeoutSeconds,
        )
        return NodeOutput(Unit, route = context.routeOf(answer, ExecutionRoute.OUT, ExecutionRoute.OUT))
    }
}
