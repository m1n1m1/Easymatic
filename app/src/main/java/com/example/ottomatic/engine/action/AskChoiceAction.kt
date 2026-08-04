package com.example.ottomatic.engine.action

import com.example.ottomatic.core.model.PortName
import com.example.ottomatic.core.service.LogLevel
import com.example.ottomatic.core.service.PromptAnswer
import com.example.ottomatic.core.service.PromptRequest
import com.example.ottomatic.domain.model.Direction
import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.NodeIcon
import com.example.ottomatic.domain.model.Port
import com.example.ottomatic.domain.model.PortKind
import com.example.ottomatic.domain.model.config.Label
import com.example.ottomatic.domain.model.config.Multiline
import com.example.ottomatic.domain.model.config.Wired
import com.example.ottomatic.domain.model.schema.Item
import com.example.ottomatic.domain.model.schema.ItemSchema
import com.example.ottomatic.domain.registry.DIALOG_CHOICE_OUT
import com.example.ottomatic.domain.registry.DIALOG_CHOICE_TYPE_ID
import com.example.ottomatic.domain.registry.DIALOG_INDEX_OUT
import com.example.ottomatic.engine.ExecOutputs
import com.example.ottomatic.engine.ExecutionContext
import com.example.ottomatic.engine.ExecutionRoute
import com.example.ottomatic.engine.NodeInput
import com.example.ottomatic.engine.NodeOutput
import com.example.ottomatic.engine.RawAction
import com.example.ottomatic.engine.effectNode
import kotlinx.serialization.Serializable

/**
 * Config for `action.dialog_choice`.
 *
 * [options] is `@Wired @Multiline` for the reason `transform.split_text`'s input
 * is: one field is then both the literal list — type one per line — and the
 * dynamic one, wired from an SMS body, an HTTP response or a variable. A second
 * field for "or take them from here" would be a second answer to the same
 * question, and the two could disagree.
 *
 * There is no confirm button label: tapping an option *is* the confirmation, the
 * way every list dialog on the platform behaves.
 */
@Serializable
data class AskChoiceConfig(
    @Label("Title") val title: String = "Ottomatic",
    @Label("Question") @Multiline @Wired val message: String = "",
    @Label("Options — one per line") @Multiline @Wired val options: String = "",
    @Label("Cancel button") val cancelLabel: String = "Cancel",
    @Label("Give up after (seconds)") val timeoutSeconds: Int = 0,
)

/**
 * `action.dialog_choice` — offers a list and reports which entry was picked.
 *
 * Shortcuts' *Choose from Menu* and Automate's *Dialog choice*, with one
 * difference that matters: there is no exec branch per option. A menu with one
 * outgoing wire per entry would make the node's shape depend on a text field, and
 * would be a third way of branching beside `action.if` and this family's own
 * confirmed/cancelled. Instead the answer leaves as *data* — `Choice` and `Index`
 * — and the graph branches on it with the comparison it already has.
 *
 * The pair is named the way `action.for_each` names its two: `Choice` is what was
 * picked, `Index` is where it sat. Most macros want the first; the second is what
 * makes "the third one" expressible without matching on text that may be
 * translated or edited.
 *
 * An empty list cancels rather than showing an empty dialog — a popup offering
 * nothing is a dead end the user cannot even refuse — and says so at WARN,
 * because a wired options field that arrived blank looks identical to one nobody
 * filled in.
 */
class AskChoiceAction : RawAction<AskChoiceConfig> {

    override val definition = effectNode<AskChoiceConfig>(
        typeId = DIALOG_CHOICE_TYPE_ID.value,
        displayName = "Ask to Choose",
        description = "Shows a popup dialog offering a list of options and reports which one was picked",
        category = NodeCategory.INTERACTION,
        icon = NodeIcon.CHOICE,
        execOutputs = ExecOutputs.DECISION,
        extraPorts = listOf(
            Port(DIALOG_CHOICE_OUT, PortKind.DATA, Direction.OUT, ItemSchema.Primitive(String::class), "Choice"),
            Port(DIALOG_INDEX_OUT, PortKind.DATA, Direction.OUT, ItemSchema.Primitive(Int::class), "Index"),
        ),
        // Only so `dialogEffectivePorts` is asked whether to draw `timed_out`;
        // both data ports are fixed.
        hasDynamicPorts = true,
        permissions = listOf(OVERLAY_PERMISSION),
    )

    override suspend fun executeRaw(
        config: AskChoiceConfig,
        input: NodeInput,
        context: ExecutionContext,
    ): NodeOutput<Map<PortName, Item>> {
        val options = optionsOf(config.options)
        if (options.isEmpty()) {
            context.log("No options to choose from, so there is nothing to ask", LogLevel.WARN)
            return NodeOutput(emptyMap(), route = ExecutionRoute.CANCELLED)
        }
        val answer = context.askUser(
            request = PromptRequest(
                title = config.title,
                message = config.message,
                // Never rendered — a list dialog confirms by being tapped — but the
                // request has no shape for "no confirm button", and inventing one
                // for a field nobody reads would be worse than a sensible word.
                confirmLabel = "OK",
                cancelLabel = config.cancelLabel,
                options = options,
            ),
            timeoutSeconds = config.timeoutSeconds,
        )
        val route = context.routeOf(answer, ExecutionRoute.CONFIRMED, ExecutionRoute.CANCELLED)
        val picked = (answer as? PromptAnswer.Confirmed)?.takeIf { it.index in options.indices }
        return NodeOutput(
            value = picked?.let {
                mapOf(
                    DIALOG_CHOICE_OUT to Item(options[it.index], ItemSchema.Primitive(String::class)),
                    DIALOG_INDEX_OUT to Item(it.index, ItemSchema.Primitive(Int::class)),
                )
            }.orEmpty(),
            route = route,
        )
    }
}
