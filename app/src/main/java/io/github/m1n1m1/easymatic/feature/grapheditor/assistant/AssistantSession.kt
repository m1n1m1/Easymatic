package io.github.m1n1m1.easymatic.feature.grapheditor.assistant

import io.github.m1n1m1.easymatic.core.model.NodeId
import io.github.m1n1m1.easymatic.core.model.NodeTypeId
import io.github.m1n1m1.easymatic.core.service.Ai
import io.github.m1n1m1.easymatic.core.service.AiRequest
import io.github.m1n1m1.easymatic.core.service.AiToolCall
import io.github.m1n1m1.easymatic.core.service.AiToolLimits
import io.github.m1n1m1.easymatic.core.service.AiToolResult
import io.github.m1n1m1.easymatic.domain.model.NodeTypeDefinition
import io.github.m1n1m1.easymatic.domain.model.Workflow
import io.github.m1n1m1.easymatic.engine.ai.GraphAssistantPrompt
import io.github.m1n1m1.easymatic.engine.ai.GraphEditTools
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The graph, as the assistant is allowed to touch it.
 *
 * A narrow interface onto `GraphEditorViewModel` rather than the ViewModel itself, so a
 * turn can be driven in a JVM test with no Android and no Compose. It also states the
 * whole of what the assistant can reach, which is a shorter list than the editor's own
 * surface and is meant to stay that way.
 */
interface AssistantEditor {

    val workflow: Workflow

    /** Whether the user has taken the canvas since the last [resetCanvasFollow]. */
    val canvasMovedByUser: StateFlow<Boolean>

    fun apply(workflow: Workflow)

    fun arrange(nodeIds: Set<NodeId>)

    fun resetCanvasFollow()

    /** The translated name a node carries when it is placed. */
    fun nameOf(definition: NodeTypeDefinition): String
}

/**
 * Something the app has to say about a turn, as a fact rather than a sentence.
 *
 * The model's own words arrive as text and stay as text — they are in whatever language
 * it answered in, and nothing here could translate them. These five are *ours*, so they
 * are worded in `feature/`'s composables from string resources like every other line the
 * user reads. Keeping them out of this file is also what keeps the session testable
 * without a `Context`.
 */
enum class AssistantNotice {
    NO_MODEL_CHOSEN,
    CANCELLED,
    FAILED,
    NOTHING_SAID,
    UNDONE,
}

/** One line of the conversation. */
sealed interface AssistantMessage {

    data class FromUser(val text: String) : AssistantMessage

    /** The model's answer, or the provider's own sentence about why there is none. */
    data class FromAssistant(val text: String, val isError: Boolean = false) : AssistantMessage

    /** Something the app is saying, worded where the strings live. */
    data class Notice(val notice: AssistantNotice) : AssistantMessage
}

/**
 * What a turn changed, for the pill to state in one line.
 *
 * Counted from the snapshot rather than tallied as the tools run, because a turn that
 * adds a node and then thinks better of it changed nothing, and saying "1 node added"
 * about that would be a lie the canvas immediately contradicts.
 */
data class AssistantChanges(
    val nodesAdded: Int = 0,
    val nodesRemoved: Int = 0,
    val wiresAdded: Int = 0,
    val wiresRemoved: Int = 0,
) {
    val isEmpty: Boolean get() = nodesAdded == 0 && nodesRemoved == 0 && wiresAdded == 0 && wiresRemoved == 0

    companion object {
        fun between(before: Workflow, after: Workflow): AssistantChanges {
            val beforeNodes = before.nodes.map { it.id }.toSet()
            val afterNodes = after.nodes.map { it.id }.toSet()
            val beforeWires = before.wireIds()
            val afterWires = after.wireIds()
            return AssistantChanges(
                nodesAdded = (afterNodes - beforeNodes).size,
                nodesRemoved = (beforeNodes - afterNodes).size,
                wiresAdded = (afterWires - beforeWires).size,
                wiresRemoved = (beforeWires - afterWires).size,
            )
        }

        private fun Workflow.wireIds(): Set<String> =
            (execConnections.map { it.id } + dataConnections.map { it.id }).toSet()
    }
}

/** One tool call in flight, for the pill to word. */
data class AssistantStep(val tool: String, val typeId: NodeTypeId? = null)

/**
 * Where the conversation has got to.
 *
 * **Separate from how big the overlay is**, which it used to be fused with. One state
 * cannot answer both questions: the turn is *what is happening*, and expanded or
 * minimized is *how much of it the user wants to see* — and the second is theirs to
 * change at any moment, including in the middle of the first.
 */
sealed interface AssistantTurn {

    /** Nothing in flight: the overlay is a composer, or a pill inviting one. */
    data object Idle : AssistantTurn

    /** The model is working, and the canvas is following it. */
    data class Working(val step: AssistantStep? = null) : AssistantTurn

    /** It finished. The pill states what changed, with Undo beside it. */
    data class Done(val changes: AssistantChanges, val isError: Boolean) : AssistantTurn
}

data class AssistantState(
    /** Whether the overlay is on screen at all. */
    val isOpen: Boolean = false,
    /**
     * Whether it is the tall panel rather than the pill.
     *
     * Persisted across [AssistantSession.close] and [AssistantSession.open] rather than
     * reset, so leaving and coming back lands where the user left it — which is also what
     * makes resuming a turn work without a second piece of state remembering it.
     */
    val isExpanded: Boolean = true,
    val turn: AssistantTurn = AssistantTurn.Idle,
    /**
     * What is typed and not yet sent.
     *
     * Here rather than remembered in the composer, because the composer is inside the
     * `AnimatedContent` that swaps the two sizes — so a half-written question would be
     * thrown away by the very gesture that exists to go and look at the canvas while
     * writing it.
     */
    val draft: String = "",
    val transcript: List<AssistantMessage> = emptyList(),
    val modelRef: String = "",
    /** What the canvas should bring into view, or empty to leave it alone. */
    val focusNodes: Set<NodeId> = emptySet(),
    /** True while a snapshot from the last turn is still restorable. */
    val canUndo: Boolean = false,
)

/**
 * One conversation about one workflow.
 *
 * **Each message is a fresh [Ai.converse]**, not a thread the provider keeps. `AiRequest`
 * carries a single prompt and `runToolExchange` seeds its exchange with one `Ask`;
 * threading prior turns through would mean touching `core`, `data` and all three
 * protocols for no gain here — the *graph* is the state, and restating it each turn is
 * exactly what stops the model acting on a stale picture of it. The transcript goes in
 * the prompt so the model still hears what was already said.
 *
 * **Edits land live.** Each tool call is applied to the editor as it happens rather than
 * to a draft committed at the end, which is what the user is watching for. The price is
 * that a cancelled turn leaves a half-built graph, and the snapshot is what pays it.
 */
@Suppress("TooManyFunctions") // One per thing the overlay can ask of a turn; a bag object would only rename them.
class AssistantSession(
    private val ai: Ai,
    private val editor: AssistantEditor,
    private val scope: CoroutineScope,
    private val onModelChosen: (String) -> Unit = {},
    initialModelRef: String = "",
) {

    private val _state = MutableStateFlow(AssistantState(modelRef = initialModelRef))
    val state: StateFlow<AssistantState> = _state.asStateFlow()

    /** The graph as it stood before the last turn, or null when there is nothing to undo. */
    private var snapshot: Workflow? = null

    private var turn: Job? = null

    /**
     * Shows the overlay, at whatever size it was left.
     *
     * Reopening mid-turn therefore lands on the pill the turn collapsed to, and reopening
     * after one lands on the result — both for free, because size is now its own piece of
     * state rather than something derived from the turn.
     */
    fun open() {
        _state.update { it.copy(isOpen = true) }
    }

    /**
     * Records which end the panel settled at.
     *
     * Only the *end*. How far open it is at any instant belongs to the finger and lives in
     * the overlay as an animation; what is worth keeping here is the decision, because
     * that is what has to survive closing and reopening and what the back gesture reads.
     */
    fun setExpanded(expanded: Boolean) {
        _state.update { it.copy(isExpanded = expanded) }
    }

    /** Opens the conversation to its full height. */
    fun expand() = setExpanded(true)

    /**
     * Folds the conversation away, leaving the handle, the status and the input.
     *
     * Not a close: a turn in flight is still reported, and what was being typed is still
     * there. This is what a downward drag, a tap outside the panel and the first back
     * gesture all do.
     */
    fun minimize() = setExpanded(false)

    fun toggleExpanded() {
        _state.update { it.copy(isExpanded = !it.isExpanded) }
    }

    fun editDraft(text: String) {
        _state.update { it.copy(draft = text) }
    }

    /**
     * Forgets the conversation and starts a new one.
     *
     * **It does not touch the graph**, and that is the whole distinction from [undo]: what
     * was built stays built, and only the model's memory of how it got there is dropped.
     * The reason to want it is that the transcript is restated in every prompt, so a long
     * exchange that went somewhere unhelpful goes on costing tokens and steering answers
     * until it is cleared.
     *
     * The undo snapshot goes with it. Offering to rewind the graph to before a turn the
     * conversation no longer remembers would be a button whose effect nothing on screen
     * explains any more.
     *
     * Refused mid-turn rather than cancelling: stopping the model is the ✕ on the status
     * row, and one button that sometimes also aborts a billed call is one button too many.
     */
    fun restart() {
        if (turn?.isActive == true) return
        snapshot = null
        _state.update {
            it.copy(
                turn = AssistantTurn.Idle,
                transcript = emptyList(),
                draft = "",
                canUndo = false,
                focusNodes = emptySet(),
            )
        }
    }

    /** Leaves the overlay. A turn in flight keeps running; the pill comes back with it. */
    fun close() {
        _state.update { it.copy(isOpen = false, focusNodes = emptySet()) }
    }

    fun chooseModel(modelRef: String) {
        _state.update { it.copy(modelRef = modelRef) }
        onModelChosen(modelRef)
    }

    /**
     * Stops the turn, and says so at once.
     *
     * **The notice is written here rather than in the turn's own cancellation branch**,
     * which is where it used to be — and that is what "Stop does not stop" was.
     * Cancellation is cooperative, so a turn sitting in a socket read went on being
     * reported as working, with the composer still refusing the next question, until the
     * provider got round to answering: up to a minute and a half of the ✕ looking like it
     * did nothing. What the user pressed is a decision, and a decision is knowable
     * immediately; the coroutine unwinding behind it has nothing left to announce.
     *
     * [io.github.m1n1m1.easymatic.data.ai.AiTransport] closes the socket on cancellation, so
     * the request is genuinely dropped rather than merely stopped being listened to.
     */
    fun cancel() {
        val running = turn?.takeIf { it.isActive } ?: return
        // Forgotten before it is cancelled, so `send` and `restart` are usable at once
        // rather than waiting on a turn whose result can no longer reach anything.
        turn = null
        running.cancel()
        // What was half-built stays on the canvas, and the snapshot is what offers to take
        // it back — which is the whole reason `send` takes one.
        finish(
            before = snapshot ?: editor.workflow,
            message = AssistantMessage.Notice(AssistantNotice.CANCELLED),
            isError = true,
        )
    }

    /**
     * Puts the graph back as it was before the last turn.
     *
     * The app has no undo stack, and this is deliberately not the beginning of one: it
     * restores exactly one snapshot, taken at exactly one moment the user chose by
     * pressing send. Anything else they did between turns is theirs and is not in it.
     */
    fun undo() {
        val restored = snapshot ?: return
        snapshot = null
        editor.apply(restored)
        _state.update {
            it.copy(
                // Back to the composer, expanded: undoing is a decision to say something
                // else, and the next thing the user does is type it.
                turn = AssistantTurn.Idle,
                isExpanded = true,
                canUndo = false,
                focusNodes = emptySet(),
                transcript = it.transcript + AssistantMessage.Notice(AssistantNotice.UNDONE),
            )
        }
    }

    /**
     * Clears the result row, leaving the panel open.
     *
     * A decision that the result has been *read*, not a decision to leave — the input is
     * still there and the next thing to do is usually to ask for something else. [close] is
     * the other button, on the handle, and it keeps an unread result for the next open.
     */
    fun dismissResult() {
        if (_state.value.turn !is AssistantTurn.Done) return
        _state.update { it.copy(turn = AssistantTurn.Idle, canUndo = false, focusNodes = emptySet()) }
    }

    fun send(message: String) {
        val text = message.trim()
        if (text.isEmpty() || turn?.isActive == true) return
        val modelRef = _state.value.modelRef
        if (modelRef.isBlank()) {
            _state.update {
                it.copy(transcript = it.transcript + AssistantMessage.Notice(AssistantNotice.NO_MODEL_CHOSEN))
            }
            return
        }
        val before = editor.workflow
        snapshot = before
        val added = mutableSetOf<NodeId>()
        // Nodes the model placed itself, which the end-of-turn layout then leaves alone.
        val placed = mutableSetOf<NodeId>()
        val history = _state.value.transcript
        _state.update {
            it.copy(
                turn = AssistantTurn.Working(),
                // Sending is the moment the canvas becomes the thing to watch, so the
                // panel gets out of its way. The handle puts it back at any time.
                isExpanded = false,
                draft = "",
                transcript = it.transcript + AssistantMessage.FromUser(text),
                focusNodes = emptySet(),
                canUndo = false,
            )
        }
        editor.resetCanvasFollow()

        turn = scope.launch {
            val reply = runCatching {
                ai.converse(
                    request = AiRequest(
                        modelRef = modelRef,
                        prompt = GraphAssistantPrompt.turn(historyFor(history), before, text),
                        systemInstruction = GraphAssistantPrompt.instruction(),
                        // No opinion, deliberately: a turn's room is the model profile's
                        // "Longest reply" setting, which is the only place the user can
                        // change it — this surface has no field of its own, so stating a
                        // number here would make theirs unreachable.
                        maxOutputTokens = NO_REPLY_LIMIT_OF_OUR_OWN,
                    ),
                    tools = GraphEditTools.tools,
                    // The ceiling rather than the default: authoring a whole macro is a
                    // read, a describe per node type, a placement each, the wiring and a
                    // validate, which is far more calls than "ask the model something and
                    // let it act once".
                    maxTurns = AiToolLimits.MAX_TURNS,
                    invoke = { call -> runTool(call, added, placed) },
                )
            }.getOrElse { cause ->
                // A cancelled turn is not a failed one, and [cancel] has already said so.
                // The rethrow is all that is left of it: it keeps a scope torn down with
                // the editor from being reported as a provider failure.
                if (cause is CancellationException) throw cause
                val message = cause.message
                    ?.let { AssistantMessage.FromAssistant(it, isError = true) }
                    ?: AssistantMessage.Notice(AssistantNotice.FAILED)
                finish(before, message, isError = true)
                return@launch
            }
            editor.arrange(added - placed)
            // A turn that used its last call on a tool answers nothing; `AiReply` reports
            // that as an error rather than as an empty answer, and either way the graph is
            // what actually changed, so the pill says so.
            val message = when {
                reply.text.isNotBlank() -> AssistantMessage.FromAssistant(reply.text)
                reply.error.isNotBlank() -> AssistantMessage.FromAssistant(reply.error, isError = true)
                else -> AssistantMessage.Notice(AssistantNotice.NOTHING_SAID)
            }
            finish(before, message, isError = reply.error.isNotBlank())
        }
    }

    /**
     * Applies one tool call, live.
     *
     * The focus set is only updated when the call actually touched something, so a run of
     * reads leaves the canvas where the last edit put it rather than snapping back to
     * nothing. It stops updating entirely once the user has moved the canvas themselves.
     */
    private fun runTool(
        call: AiToolCall,
        added: MutableSet<NodeId>,
        placed: MutableSet<NodeId>,
    ): AiToolResult {
        _state.update { it.copy(turn = AssistantTurn.Working(stepOf(call))) }
        val edit = GraphEditTools.apply(editor.workflow, call, editor::nameOf)
        if (edit.workflow != editor.workflow) editor.apply(edit.workflow)
        added += edit.addedNodes
        placed += edit.movedNodes
        if (edit.touchedNodes.isNotEmpty() && !editor.canvasMovedByUser.value) {
            _state.update { it.copy(focusNodes = edit.touchedNodes) }
        }
        return edit.result
    }

    private fun finish(before: Workflow, message: AssistantMessage, isError: Boolean) {
        val changes = AssistantChanges.between(before, editor.workflow)
        _state.update {
            it.copy(
                turn = AssistantTurn.Done(changes, isError),
                transcript = it.transcript + message,
                canUndo = !changes.isEmpty,
                focusNodes = emptySet(),
            )
        }
        // Nothing to put back is nothing to offer putting back: an Undo that would do
        // nothing is worse than no Undo, because it reads as though something happened.
        if (changes.isEmpty) snapshot = null
    }

    /** Which node type a call is about, so the pill can name it in the user's language. */
    private fun stepOf(call: AiToolCall): AssistantStep = AssistantStep(
        tool = call.name,
        typeId = call.arguments[TYPE_ID_ARGUMENT]?.let(::NodeTypeId),
    )

    /**
     * The transcript as the prompt builder wants it.
     *
     * The wording lives in `engine/ai/` — every string under `feature/` is one a person
     * reads and a translator owns, and this is neither. A [AssistantMessage.Notice] is
     * dropped: it is the app talking, not either side of the conversation, and there is
     * nothing in it the model could act on.
     */
    private fun historyFor(transcript: List<AssistantMessage>): List<GraphAssistantPrompt.PriorTurn> =
        transcript.takeLast(TRANSCRIPT_TURNS).mapNotNull { entry ->
            when (entry) {
                is AssistantMessage.FromUser -> GraphAssistantPrompt.PriorTurn(fromUser = true, text = entry.text)
                is AssistantMessage.FromAssistant -> GraphAssistantPrompt.PriorTurn(fromUser = false, text = entry.text)
                is AssistantMessage.Notice -> null
            }
        }

    internal companion object {

        const val TYPE_ID_ARGUMENT = "type_id"

        /**
         * How much of the conversation is restated.
         *
         * Enough for "and now make it do X as well" to make sense, and short enough that a
         * long session does not grow its own prompt without bound. What is *not* restated
         * is not lost: the graph carries everything that was actually built.
         */
        const val TRANSCRIPT_TURNS = 8

        /**
         * Asks for no particular reply length, leaving it to the model profile.
         *
         * **The number used to live here and was the wrong place for it.** A turn spends
         * its budget on tool calls before a word of the answer is written, so `AiRequest`'s
         * 1024 default — sized for a macro firing unattended, where a runaway answer spends
         * quota with nobody watching — ran out mid-build and produced "the reply was cut off
         * before any text was produced", advice aimed at a config field this surface has
         * not got. A constant here fixed the size and left it just as unreachable.
         *
         * It is now `AiModelProfile.maxOutputTokens`, edited beside the model it applies
         * to, and stating anything here would put it back out of reach.
         */
        const val NO_REPLY_LIMIT_OF_OUR_OWN = 0
    }
}
