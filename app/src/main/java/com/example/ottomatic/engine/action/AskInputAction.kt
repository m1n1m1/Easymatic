package com.example.ottomatic.engine.action

import com.example.ottomatic.core.model.PortName
import com.example.ottomatic.core.service.PromptAnswer
import com.example.ottomatic.core.service.PromptField
import com.example.ottomatic.core.service.PromptRequest
import com.example.ottomatic.domain.model.Direction
import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.NodeIcon
import com.example.ottomatic.domain.model.Port
import com.example.ottomatic.domain.model.PortKind
import com.example.ottomatic.domain.model.config.Label
import com.example.ottomatic.domain.model.config.Multiline
import com.example.ottomatic.domain.model.config.ValueType
import com.example.ottomatic.domain.model.config.VisibleWhen
import com.example.ottomatic.domain.model.config.Wired
import com.example.ottomatic.domain.model.schema.Item
import com.example.ottomatic.domain.model.schema.ItemSchema
import com.example.ottomatic.domain.registry.DIALOG_INPUT_TYPE_ID
import com.example.ottomatic.domain.registry.DIALOG_VALUE_OUT
import com.example.ottomatic.engine.ExecOutputs
import com.example.ottomatic.engine.ExecutionContext
import com.example.ottomatic.engine.ExecutionRoute
import com.example.ottomatic.engine.NodeInput
import com.example.ottomatic.engine.NodeOutput
import com.example.ottomatic.engine.RawAction
import com.example.ottomatic.engine.adaptiveNode
import kotlinx.serialization.Serializable

/**
 * Config for `action.dialog_input`.
 *
 * [answerType] is not called `type` because `pruneRetypedEdges` and the effective
 * port resolution both key on config *names*, and "type" is already the comparison
 * nodes' and `transform.json_read`'s. A shared name would not break anything today
 * and would be a trap the first time someone reused one of those helpers.
 *
 * [prefilled] is `@Wired` so the last known value can be offered back — asking
 * "how many minutes?" with the previous answer already in the box is most of what
 * makes such a question tolerable to answer twice.
 */
@Serializable
data class AskInputConfig(
    @Label("Title") val title: String = "Ottomatic",
    @Label("Question") @Multiline @Wired val message: String = "",
    @Label("Answer type") val answerType: ValueType = ValueType.TEXT,
    @Label("Prefilled") @Wired val prefilled: String = "",
    @Label("Hint") val hint: String = "",
    @Label("Multiple lines") @VisibleWhen("answerType", "TEXT") val multiline: Boolean = false,
    @Label("Confirm button") val confirmLabel: String = "OK",
    @Label("Cancel button") val cancelLabel: String = "Cancel",
    @Label("Give up after (seconds)") val timeoutSeconds: Int = 0,
)

/**
 * `action.dialog_input` — asks the user for one value and puts it on a port.
 *
 * The counterpart of `action.dialog_confirm`: that one decides, this one
 * supplies. "Snooze for how long?", "what should I call this file?", "what is
 * the code they just sent you?" — none of which can be known when the macro is
 * written.
 *
 * **The answer arrives typed.** The renderer hands back text, because a dialog
 * knows about keyboards and not about `ItemSchema`; the conversion to
 * [AskInputConfig.answerType] happens here, through the same total
 * `ValueType.convert` the autocast uses. That is safe for the reason the totality
 * rule asks for: the chosen type is *visible*, sitting on the card as a dropdown,
 * so "seven became 0" is something the user can see and correct. The port is
 * retyped to match in `effectivePorts`, so an answer asked for as a whole number
 * drops straight into a numeric input with no conversion node in the wire.
 *
 * **A cancelled dialog puts nothing on the port.** Not the empty string, not a
 * zero: the `cancelled` branch is where that outcome is handled, and publishing a
 * value there would let a downstream node read a fabricated answer if it were
 * ever wired from both branches.
 */
class AskInputAction : RawAction<AskInputConfig> {

    override val definition = adaptiveNode<AskInputConfig>(
        typeId = DIALOG_INPUT_TYPE_ID.value,
        displayName = "Ask for Input",
        description = "Asks the user to type a value in a popup dialog and puts it on a port",
        category = NodeCategory.INTERACTION,
        icon = NodeIcon.INPUT,
        // Declared as a wildcard and retyped by `dialogEffectivePorts`; declared
        // at all because the drag-into-empty-space palette reads declared ports.
        extraPorts = listOf(
            Port(DIALOG_VALUE_OUT, PortKind.DATA, Direction.OUT, ItemSchema.Wildcard, label = "Value"),
        ),
        execOutputs = ExecOutputs.DECISION,
        permissions = listOf(OVERLAY_PERMISSION),
    )

    override suspend fun executeRaw(
        config: AskInputConfig,
        input: NodeInput,
        context: ExecutionContext,
    ): NodeOutput<Map<PortName, Item>> {
        val answer = context.askUser(
            request = PromptRequest(
                title = config.title,
                message = config.message,
                confirmLabel = config.confirmLabel,
                cancelLabel = config.cancelLabel,
                field = PromptField(
                    kind = config.answerType.promptFieldKind(config.multiline),
                    initial = config.prefilled,
                    hint = config.hint,
                ),
            ),
            timeoutSeconds = config.timeoutSeconds,
        )
        val route = context.routeOf(answer, ExecutionRoute.CONFIRMED, ExecutionRoute.CANCELLED)
        val typed = (answer as? PromptAnswer.Confirmed)?.let {
            config.answerType.convert(Item(it.text, ItemSchema.Primitive(String::class)))
        }
        return NodeOutput(
            value = typed?.let { mapOf(DIALOG_VALUE_OUT to it) }.orEmpty(),
            route = route,
        )
    }
}
