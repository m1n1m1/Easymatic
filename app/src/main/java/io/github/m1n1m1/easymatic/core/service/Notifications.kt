package io.github.m1n1m1.easymatic.core.service

/**
 * Posts a notification on this device, and — when it offers buttons or a reply
 * field — waits for what the user does with it.
 *
 * A facade of its own rather than two more members on [SystemServices], for the
 * reason [Prompts] gives for not living there either: **[SystemServices] is
 * write-only.** Every member of it changes something and none of them waits for a
 * reply. [await] waits, possibly for hours.
 *
 * ### What it is *not*
 *
 * This is the **posting** side. Reading and pressing *other* apps' notifications is
 * [Messaging], which needs notification access and a
 * `NotificationListenerService`; nothing here does. The two never meet: a
 * notification this app posted is addressed by its own [NotificationRequest.tag],
 * where somebody else's is addressed by a
 * [io.github.m1n1m1.easymatic.domain.model.ConversationRef].
 *
 * ### Text and numbers in, text out
 *
 * [NotificationRequest] describes a notification in the renderer's own vocabulary —
 * a title, some labels, a colour as a number — and [NotificationAnswer] hands back
 * plain text. It speaks neither `ValueType` nor `MacroAccent`, and not only because
 * `core/` cannot see `domain/`: the node that posted knows what it asked for and
 * puts the answer on its own typed ports. It is the boundary
 * [MessengerRecipe] draws, and [Prompts] draws, in the same place.
 */
interface Notifications {

    /**
     * Posts [request] and returns a **token** to [await] the answer on, or null when
     * nothing could be posted at all — most often because notifications are switched
     * off for this app.
     *
     * A token rather than the tag, because two macros may legitimately post under the
     * same tag and only the second one is on screen; the first must not be handed the
     * second's button press.
     *
     * An unanswerable request still gets a token, which nobody then awaits. Null means
     * **nothing was posted**, and only that, so the caller can say so in the run log:
     * folding "there is nothing to wait for" into the same answer would make a
     * notification that never appeared indistinguishable from one with no buttons.
     */
    suspend fun post(request: NotificationRequest): Long?

    /**
     * Suspends until the user answers the notification [token] was posted with, and
     * answers **null when it went away unanswered** — swiped off, replaced, or
     * [timeoutMs] elapsed.
     *
     * Null rather than an "ignored" answer, because the caller's branch must then not
     * fire at all: a macro that treats being ignored as a button press is a macro that
     * acts on a decision nobody made. [Prompts] can afford to say `Cancelled` because a
     * dialog is modal and being dismissed *is* an answer; a notification sitting
     * unregarded in a shade is not.
     *
     * [timeoutMs] of 0 waits forever. Never throws; cancelling the caller stops waiting
     * and leaves the notification alone.
     */
    suspend fun await(token: Long, timeoutMs: Long): NotificationAnswer?

    /**
     * Takes down whatever this app posted under [tag]. False when nothing was there,
     * or when the tag is blank.
     */
    fun cancel(tag: String): Boolean
}

/**
 * One notification, as the platform half needs to see it.
 *
 * [answerable] is carried rather than derived from `buttons.isNotEmpty()`, even though
 * today those agree. It is the caller's statement that somebody is *listening* — which
 * is what decides whether the tap and swipe intents are worth attaching at all — and
 * the caller is the half that knows. Deriving it here would make this object's meaning
 * depend on a rule living in a node.
 *
 * [accentArgb] is `0xAARRGGBB`, with **0 meaning "no colour of ours"**; see
 * `MacroAccent.argb` for why a number crosses here rather than a resource.
 *
 * [progress] is [NO_PROGRESS] for no bar at all, `0..100` for a determinate one and
 * anything else negative for the indeterminate barber's pole. Three states in one
 * number because they are one question — *how far along is it?* — with "not
 * applicable" and "no idea" as two of the answers.
 *
 * [replyIndex] names which of [buttons] carries the text field, rather than the reply
 * being a button of its own. Android has no such thing: a reply field is a
 * `RemoteInput` *attached to an action*, so the two would have to be kept in step
 * anyway, and an index says which one in a way a duplicate label never could.
 * [NO_REPLY] when there is none.
 */
data class NotificationRequest(
    val title: String,
    val text: String,
    val tag: String,
    val buttons: List<String> = emptyList(),
    val replyIndex: Int = NO_REPLY,
    val replyHint: String = "",
    val answerable: Boolean = false,
    val accentArgb: Long = 0,
    val progress: Int = NO_PROGRESS,
    val ongoing: Boolean = false,
    val hideAfterMs: Long = 0,
) {
    companion object {
        /** [NotificationRequest.progress] for a notification with no progress bar. */
        const val NO_PROGRESS: Int = Int.MIN_VALUE

        /** [NotificationRequest.replyIndex] for a notification with no reply field. */
        const val NO_REPLY: Int = -1
    }
}

/**
 * What the user did with a notification.
 *
 * [index] is the button's position, or [TAPPED] when the body itself was tapped —
 * which is why there is no separate outcome type for a tap. A tap and a button are
 * the same event with a different answer, exactly as
 * [PromptAnswer.Confirmed] treats a typed value and a picked option.
 *
 * [reply] is what was typed into the reply field, and empty for every other button.
 */
data class NotificationAnswer(
    val label: String,
    val index: Int,
    val reply: String = "",
) {
    companion object {
        /** [NotificationAnswer.index] when the notification body was tapped. */
        const val TAPPED: Int = -1
    }
}

/**
 * A notification poster that posts nothing, for engine-only unit tests.
 *
 * Reports failure rather than success, so a test that forgets to supply one fails
 * the way a phone with notifications switched off does — the contract [NoPrompts],
 * [NoMessaging] and [NoScripts] all keep for their own sides.
 */
object NoNotifications : Notifications {

    override suspend fun post(request: NotificationRequest): Long? = null

    override suspend fun await(token: Long, timeoutMs: Long): NotificationAnswer? = null

    override fun cancel(tag: String): Boolean = false
}
