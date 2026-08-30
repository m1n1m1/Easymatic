package io.github.m1n1m1.easymatic.core.service

/**
 * Puts a question to the person holding the phone and waits for the answer.
 *
 * The one place a macro reaches *out* to the user mid-run. Everything else in
 * the graph is decided before it starts; this is what makes "shall I really?"
 * and "how many minutes?" expressible at all.
 *
 * A `core/` port with its Android half in `data/prompt/`, like [ScriptEngine]
 * and for the same three reasons: it is slow (it waits for a human), it is
 * failable, and on a device where the overlay permission has not been granted it
 * cannot happen at all. It is deliberately *not* a member of [SystemServices],
 * which is write-only — every one of that facade's members changes something and
 * none of them waits for a reply.
 *
 * ### Text in, text out
 *
 * [PromptRequest] describes a dialog in the renderer's own vocabulary — a
 * caption, a hint, which keyboard — and [PromptAnswer.Confirmed] hands back
 * plain text. It does not speak `ValueType`, and not only because `core/`
 * cannot see `domain/`: typing belongs to the graph. The node that asked knows
 * what it asked for and converts the answer itself, which is the same boundary
 * `NodeSchema.decode` draws with `asText()` and [ScriptEngine] draws with JSON.
 *
 * ### No timeout here
 *
 * A dialog that nobody answers is the caller's problem, not the renderer's: the
 * node wraps [ask] in `withTimeoutOrNull` and the resulting cancellation takes
 * the window down. That keeps this contract at two outcomes plus "not possible",
 * and keeps "how long do I wait" beside the node that shows it on the card.
 */
interface Prompts {

    /**
     * Shows [request] and suspends until the user answers it.
     *
     * Never throws: a missing permission comes back as [PromptAnswer.Unavailable]
     * because the caller has to log it and carry on either way. Cancelling the
     * calling coroutine dismisses the dialog.
     */
    suspend fun ask(request: PromptRequest): PromptAnswer
}

/**
 * One dialog, as the renderer needs to see it.
 *
 * [cancelLabel] is null for a dialog with nothing to refuse — a message that is
 * only acknowledged. [field] and [options] are mutually exclusive in practice:
 * one node asks for a value, another offers a list, and no node does both.
 */
data class PromptRequest(
    val title: String,
    val message: String,
    val confirmLabel: String,
    val cancelLabel: String?,
    val field: PromptField? = null,
    val options: List<String> = emptyList(),
)

/** The single value a dialog asks for, when it asks for one. */
data class PromptField(
    val kind: PromptFieldKind,
    val initial: String = "",
    val hint: String = "",
)

/**
 * Which control a [PromptField] is edited with.
 *
 * Not a second type system beside `ItemSchema` and not a copy of `ValueType`:
 * this says *how the answer is typed in* — which keyboard, a switch rather than
 * a text box — and stops there. What the answer then *is* is decided by the node,
 * which converts the text it gets back.
 */
enum class PromptFieldKind {
    TEXT,
    MULTILINE_TEXT,
    NUMBER,
    WHOLE_NUMBER,
    YES_OR_NO,

    /**
     * A moment in time, typed rather than picked. The parser on the other side is
     * deliberately lenient (`18:00`, `2026-07-27`, ISO with an offset, epoch
     * millis), which covers far more of what people actually type than a calendar
     * widget covers of what they actually mean.
     */
    DATE_TIME,
}

/** What the user did with a [PromptRequest]. */
sealed interface PromptAnswer {

    /**
     * The user confirmed. [text] is what they typed, or the option they picked;
     * [index] is that option's position, or -1 when the dialog offered no list.
     */
    data class Confirmed(val text: String = "", val index: Int = -1) : PromptAnswer

    /** The user refused, pressed back, or tapped outside the dialog. */
    data object Cancelled : PromptAnswer

    /**
     * Nothing could be shown — most often because permission to draw over other
     * apps has not been granted. Distinct from [Cancelled] because it is a
     * property of the phone rather than an answer, and the node logs it as such:
     * an unanswerable question must not read as a refusal in the run log, even
     * though it takes the same branch.
     */
    data class Unavailable(val reason: String) : PromptAnswer
}

/**
 * A renderer that shows nothing, for engine-only unit tests and previews.
 *
 * Reports [PromptAnswer.Unavailable] rather than a confirmation, so a test that
 * forgets to supply one fails the way a phone without the permission does —
 * the contract [NoScripts] and [UnknownDeviceState] keep for their own sides.
 */
object NoPrompts : Prompts {
    override suspend fun ask(request: PromptRequest): PromptAnswer =
        PromptAnswer.Unavailable("this build cannot show dialogs")
}
