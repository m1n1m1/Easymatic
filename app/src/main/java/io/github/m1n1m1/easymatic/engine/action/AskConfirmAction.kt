package io.github.m1n1m1.easymatic.engine.action

import io.github.m1n1m1.easymatic.core.service.PromptRequest
import io.github.m1n1m1.easymatic.domain.model.NodeCategory
import io.github.m1n1m1.easymatic.domain.model.NodeIcon
import io.github.m1n1m1.easymatic.domain.model.config.Label
import io.github.m1n1m1.easymatic.domain.model.config.Multiline
import io.github.m1n1m1.easymatic.domain.model.config.Wired
import io.github.m1n1m1.easymatic.domain.registry.DIALOG_CONFIRM_TYPE_ID
import io.github.m1n1m1.easymatic.engine.Action
import io.github.m1n1m1.easymatic.engine.ExecOutputs
import io.github.m1n1m1.easymatic.engine.ExecutionContext
import io.github.m1n1m1.easymatic.engine.ExecutionRoute
import io.github.m1n1m1.easymatic.engine.NodeOutput
import io.github.m1n1m1.easymatic.engine.effectNode
import kotlinx.serialization.Serializable

/** Config for `action.dialog_confirm`. */
@Serializable
data class AskConfirmConfig(
    @Label("Title") val title: String = "Easymatic",
    @Label("Question") @Multiline @Wired val message: String = "",
    @Label("Confirm button") val confirmLabel: String = "Yes",
    @Label("Cancel button") val cancelLabel: String = "No",
    @Label("Give up after (seconds)") val timeoutSeconds: Int = 0,
)

/**
 * `action.dialog_confirm` — asks a yes/no question and branches on the answer.
 *
 * The node that makes a macro checkable: "the geofence says I have left the
 * house — shall I really turn everything off?". Without it, every automation is
 * either fully trusted or not written at all.
 *
 * The branch is the node's own, not an `action.if` downstream, and that is not a
 * second conditional sneaking into the graph. `action.if` remains the only place
 * a *comparison* lives; this routes on an answer that was never a value in the
 * first place, the way a loop routes on having run out of passes.
 *
 * Back, a tap outside and the cancel button are all "cancelled": a dialog nobody
 * agreed with has not been agreed with, however it went away.
 */
class AskConfirmAction : Action<AskConfirmConfig, Unit> {

    override val definition = effectNode<AskConfirmConfig>(
        typeId = DIALOG_CONFIRM_TYPE_ID.value,
        displayName = "Ask Yes or No",
        description = "Asks the user a yes/no question in a popup dialog and branches on the answer",
        category = NodeCategory.INTERACTION,
        icon = NodeIcon.QUESTION,
        execOutputs = ExecOutputs.DECISION,
        hasDynamicPorts = true,
        permissions = listOf(OVERLAY_PERMISSION),
    )

    override suspend fun execute(input: AskConfirmConfig, context: ExecutionContext): NodeOutput<Unit> {
        val answer = context.askUser(
            request = PromptRequest(
                title = input.title,
                message = input.message,
                confirmLabel = input.confirmLabel,
                cancelLabel = input.cancelLabel,
            ),
            timeoutSeconds = input.timeoutSeconds,
        )
        return NodeOutput(
            value = Unit,
            route = context.routeOf(answer, ExecutionRoute.CONFIRMED, ExecutionRoute.CANCELLED),
        )
    }
}
