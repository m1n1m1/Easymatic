package com.example.ottomatic.engine.action

import com.example.ottomatic.core.model.PortName
import com.example.ottomatic.core.service.LogLevel
import com.example.ottomatic.core.service.NotificationAnswer
import com.example.ottomatic.core.service.NotificationRequest
import com.example.ottomatic.domain.model.Direction
import com.example.ottomatic.domain.model.MacroAccent
import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.NodeIcon
import com.example.ottomatic.domain.model.Port
import com.example.ottomatic.domain.model.PortKind
import com.example.ottomatic.domain.model.WorkflowNode
import com.example.ottomatic.domain.model.config.Label
import com.example.ottomatic.domain.model.config.Multiline
import com.example.ottomatic.domain.model.config.VisibleWhen
import com.example.ottomatic.domain.model.config.Wired
import com.example.ottomatic.domain.model.schema.Item
import com.example.ottomatic.domain.model.schema.ItemSchema
import com.example.ottomatic.domain.registry.NOTIFY_BUTTON_OUT
import com.example.ottomatic.domain.registry.NOTIFY_INDEX_OUT
import com.example.ottomatic.domain.registry.NOTIFY_REPLY_OUT
import com.example.ottomatic.domain.registry.NOTIFY_TAG_OUT
import com.example.ottomatic.domain.registry.NOTIFY_TYPE_ID
import com.example.ottomatic.engine.EncodedNodeOutput
import com.example.ottomatic.engine.ExecOutputs
import com.example.ottomatic.engine.ExecutionContext
import com.example.ottomatic.engine.ExecutionRoute
import com.example.ottomatic.engine.Fork
import com.example.ottomatic.engine.ForkAction
import com.example.ottomatic.engine.NodeInput
import com.example.ottomatic.engine.NodeOutput
import com.example.ottomatic.engine.effectNode
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable

/**
 * Config for `action.notify`.
 *
 * [title] and [text] keep the names and meanings they had when this node could do
 * nothing else, so every saved macro using it goes on working untouched.
 *
 * [buttons] is `@Multiline @Wired` for `action.dialog_choice`'s reason, down to
 * sharing its [optionsOf]: one field is then both the literal list — type one per
 * line — and the dynamic one, wired from a variable, an HTTP response or
 * `transform.list_join`. A second field for "or take them from here" would be a
 * second answer to the same question.
 *
 * **[timeoutSeconds] is one field doing two jobs on purpose.** It hides the
 * notification *and* gives up waiting for it. Two fields — "disappear after" beside
 * "give up after" — would be two answers to one question and could be set to
 * contradict each other, leaving a notification on screen that no longer reaches
 * anything. Zero means neither: it stays until something is done with it.
 *
 * [tag] does **two** things, and its label names only one of them. It is what
 * `action.notify_cancel` addresses — the part somebody setting the field is thinking
 * about — and it is also the notification's *identity*, so posting again under the
 * same tag replaces what was there rather than stacking a second one up. That second
 * half is what makes a progress bar possible, and it stays out of the label because a
 * row nobody finishes reading explains nothing at all. Blank becomes `node:<nodeId>`,
 * which the `Posted as` port hands back.
 */
@Suppress("LongParameterList") // One property per form field; a config class is a flat declaration.
@Serializable
data class NotifyConfig(
    @Label("Title") val title: String = "Ottomatic",
    @Label("Text") @Multiline @Wired val text: String = "Workflow ran",

    @Label("Buttons — one per line") @Multiline @Wired val buttons: String = "",
    @Label("Reply field") val replyField: Boolean = false,
    @VisibleWhen("replyField", "true") @Label("Reply button") val replyLabel: String = "Reply",
    @VisibleWhen("replyField", "true") @Label("Reply hint") val replyHint: String = "",
    @Label("Disappear after (seconds)") val timeoutSeconds: Int = 0,

    @Label("Colour") val accent: MacroAccent = MacroAccent.SYSTEM,
    @Label("Show a progress bar") val showProgress: Boolean = false,
    @VisibleWhen("showProgress", "true")
    @Label("Progress (%, below 0 = busy)")
    @Wired
    val progress: Int = 0,
    @Label("Keep until dismissed") val ongoing: Boolean = false,

    @Label("Tag — Used for manual deletion")
    @Wired
    val tag: String = "",
) {
    /** The buttons typed into the form, before the reply one is appended. */
    private val plainButtons: List<String> get() = optionsOf(buttons)

    /** The reply button, as a list of nought or one, so both uses read the same. */
    private val replyButton: List<String>
        get() = listOfNotNull(replyLabel.trim().takeIf { replyField && it.isNotEmpty() })

    /** The buttons to draw, the reply one last, capped at what Android will show. */
    val buttonLabels: List<String> get() = (plainButtons + replyButton).take(MAX_BUTTONS)

    /**
     * Which of [buttonLabels] carries the text field, or
     * [NotificationRequest.NO_REPLY] for none.
     *
     * Computed rather than "the last one", because the cap can drop the reply button
     * off the end — and attaching a `RemoteInput` to whatever survived last would put
     * a keyboard on a button that was never meant to have one. The named constant
     * rather than a bare -1, which on the *answer* side means something else entirely
     * ([NotificationAnswer.TAPPED]).
     */
    val replyIndex: Int
        get() = if (replyButton.isEmpty()) {
            NotificationRequest.NO_REPLY
        } else {
            plainButtons.size.takeIf { it < MAX_BUTTONS } ?: NotificationRequest.NO_REPLY
        }

    /** How many buttons were asked for, whether or not they fit. */
    val buttonsAsked: Int get() = plainButtons.size + replyButton.size

    /**
     * Whether anything can be done with this notification but ignore it — which is
     * to say, whether it offers a button.
     *
     * **A tap is not enough on its own**, even though every notification is tappable.
     * Reporting one unconditionally would give every plain `action.notify` a second
     * branch it never asked for, and a switch to opt in would be a field carrying one
     * boolean's worth of meaning on a form that already has ten. A notification that
     * *does* offer a button reports its tap too, on index
     * [NotificationAnswer.TAPPED] — so "or they just opened it" stays expressible
     * wherever there was a decision to make in the first place.
     */
    val answerable: Boolean get() = buttonLabels.isNotEmpty()
}

/**
 * Action for `action.notify` — posts a notification, and, when it offers a way to
 * react, carries on again once somebody does.
 *
 * ### Why this is a fork rather than a question
 *
 * The dialog nodes *block*: `Prompts.ask` suspends the walk until the window is
 * answered, which is right for a modal window over whatever the user is looking at.
 * A notification is the opposite — it waits in a shade, possibly for hours, and the
 * rest of the macro has no reason to stand still for it. So `out` pulses at once and
 * `resumed` pulses if and when the user reacts, which is exactly `action.wait_until`'s
 * shape with a person in place of a clock. That also makes this the answer to the
 * limit the dialog family documents: an overlay does not appear over the lock screen,
 * and a notification does.
 *
 * ### Why the answer is data and not one branch per button
 *
 * `action.dialog_choice` settled this and the argument carries over unchanged: an
 * exec output per option would make the node's *shape* depend on the contents of a
 * text field, and would be a third way of branching beside `action.if`. So the answer
 * leaves on [NOTIFY_BUTTON_OUT] and [NOTIFY_INDEX_OUT] — the words `action.for_each`
 * already uses — and the graph branches on it with the comparison it has. A tap on
 * the body of a notification that *has* buttons is index [NotificationAnswer.TAPPED]
 * with an empty label, so "if it was a button, do this; otherwise open the app" is one
 * `action.if`.
 *
 * What the notification *opens* is likewise nothing this node knows: wire
 * `action.open_url` or `action.launch_app` to `resumed` and put an `action.if` in
 * front of them. A dropdown here could only ever name one destination, where the
 * whole point of a context-dependent tap is that it is more than one.
 *
 * ### Being ignored is not an answer
 *
 * Swiped away, replaced, or [NotifyConfig.timeoutSeconds] elapsed: the branch does
 * **not** fire. A macro that treated being ignored as a press would act on a decision
 * nobody made. There is no shape in [Fork] for "having decided to wait, do not pulse
 * after all" — `resume == null` says it up front and nothing says it later — so the
 * lambda cancels itself, which
 * [com.example.ottomatic.engine.WorkflowExecutor.awaitAndPulse] already handles by
 * rethrowing without pulsing and releasing the pending-wait slot in its `finally`.
 *
 * ### Two limits, stated here so they are read rather than discovered
 *
 *  - **A pending branch does not survive the process dying**, and dies when the macro
 *    is disabled — see [ForkAction]. The notification may well outlive both, and
 *    tapping it then does nothing at all. `timeoutSeconds` is the honest bound.
 *  - **Android shows three buttons**, reply button included; the rest are dropped and
 *    the run log says so rather than letting them vanish silently.
 */
class NotifyAction : ForkAction<NotifyConfig> {

    override val definition = effectNode<NotifyConfig>(
        typeId = NOTIFY_TYPE_ID.value,
        displayName = "Show Notification",
        description = "Posts a notification on this device, optionally with buttons and a reply field",
        category = NodeCategory.NOTIFICATIONS,
        icon = NodeIcon.NOTIFICATION,
        execOutputs = ExecOutputs.ANSWERABLE,
        extraPorts = listOf(
            textOut(NOTIFY_TAG_OUT, "Posted as"),
            textOut(NOTIFY_BUTTON_OUT, "Button"),
            Port(NOTIFY_INDEX_OUT, PortKind.DATA, Direction.OUT, ItemSchema.Primitive(Int::class), "Index"),
            textOut(NOTIFY_REPLY_OUT, "Reply"),
        ),
        // So `notifyEffectivePorts` is asked whether this notification can be
        // answered at all; nothing here is retyped.
        hasDynamicPorts = true,
    )

    /**
     * Posting is the whole job until somebody reacts, and reacting is not something a
     * tool call could carry back — so this node is offered to a model, unlike every
     * other fork. See [run].
     */
    override val runsWithoutAFork: Boolean = true

    override suspend fun begin(
        config: NotifyConfig,
        input: NodeInput,
        context: ExecutionContext,
    ): Fork {
        val posted = context.post(config, input.node.id.value)
        val values = mapOf(NOTIFY_TAG_OUT to Item.of(posted.tag))
        if (posted.token == null || !config.answerable) return Fork(values = values, resume = null)
        val timeoutMs = config.timeoutSeconds.coerceAtLeast(0).toLong() * MILLIS_PER_SECOND
        return Fork(
            values = values,
            resume = {
                val answer = context.notifications.await(posted.token, timeoutMs)
                if (answer == null) {
                    context.log("The notification went away without an answer", LogLevel.DEBUG)
                    throw CancellationException("Notification answered by nobody")
                }
                context.log(answered(answer))
                mapOf(
                    NOTIFY_BUTTON_OUT to Item.of(answer.label),
                    NOTIFY_INDEX_OUT to Item.of(answer.index),
                    NOTIFY_REPLY_OUT to Item.of(answer.reply),
                )
            },
        )
    }

    /**
     * The half of this node that needs no graph: post, and carry straight on.
     *
     * Reached only from an AI tool call — [com.example.ottomatic.engine.WorkflowExecutor]
     * tests for `is ForkAction` and goes to [begin] instead — and it exists because the
     * inherited stub would have made "tell me when you are done" a tool that silently did
     * nothing. Buttons still *appear* if they were configured; nothing waits for them,
     * because there is no branch on the other side of a tool call to pulse.
     *
     * [ExecutionRoute.CONTINUE] rather than the default [ExecutionRoute.OUT]: the same
     * port, but `routePort` checks the route against the declared set and this node
     * declares [ExecOutputs.ANSWERABLE].
     */
    override suspend fun run(
        node: WorkflowNode,
        data: Map<PortName, Item>,
        context: ExecutionContext,
    ): EncodedNodeOutput {
        context.post(definition.schema.decode(node.config, data), node.id.value)
        return definition.encode(NodeOutput(Unit, route = ExecutionRoute.CONTINUE))
    }

    /**
     * Hands the notification to the platform, reporting what could not be honoured.
     *
     * Shared by [begin] and [run] so a tool call and a graph run produce the same
     * notification: the two differ in what happens *after* it is on screen, and
     * nowhere else.
     */
    private suspend fun ExecutionContext.post(config: NotifyConfig, nodeId: String): Posted {
        val labels = config.buttonLabels
        if (config.buttonsAsked > labels.size) {
            log(
                "Android shows $MAX_BUTTONS buttons, so ${config.buttonsAsked - labels.size} were left off",
                LogLevel.WARN,
            )
        }
        val tag = config.tag.trim().ifEmpty { "node:$nodeId" }
        val token = notifications.post(
            NotificationRequest(
                title = config.title,
                text = config.text,
                tag = tag,
                buttons = labels,
                replyIndex = config.replyIndex,
                replyHint = config.replyHint,
                answerable = config.answerable,
                accentArgb = config.accent.argb,
                progress = if (config.showProgress) config.progress else NotificationRequest.NO_PROGRESS,
                ongoing = config.ongoing,
                hideAfterMs = config.timeoutSeconds.coerceAtLeast(0).toLong() * MILLIS_PER_SECOND,
            ),
        )
        if (token == null) log("Could not post the notification", LogLevel.WARN)
        return Posted(tag, token)
    }

    /** What [post] came back with: the tag it used, and a token when it got that far. */
    private class Posted(val tag: String, val token: Long?)

    private fun answered(answer: NotificationAnswer): String = when {
        answer.index == NotificationAnswer.TAPPED -> "Tapped"
        answer.reply.isNotEmpty() -> "'${answer.label}' with '${answer.reply}'"
        else -> "'${answer.label}' pressed"
    }
}

/**
 * A DATA output carrying text.
 *
 * Declared as an extra port rather than through `actionNode`'s typed `output` for
 * `action.wait_until`'s reason: this node has *four* of them, and — more to the point
 * — they belong to two different branches, which is a shape the single `DataOut`
 * cannot express. `tag` is known the instant the notification is posted; the other
 * three only exist once somebody has reacted.
 */
private fun textOut(name: PortName, label: String): Port = Port(
    name = name,
    kind = PortKind.DATA,
    direction = Direction.OUT,
    schema = ItemSchema.Primitive(String::class),
    label = label,
)

/** How many actions Android actually renders on a notification. */
internal const val MAX_BUTTONS = 3

private const val MILLIS_PER_SECOND = 1_000L
