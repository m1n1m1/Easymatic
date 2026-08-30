package io.github.m1n1m1.easymatic.engine.action

import io.github.m1n1m1.easymatic.core.permissions.PermissionRequirement
import io.github.m1n1m1.easymatic.core.permissions.PrerequisiteType
import io.github.m1n1m1.easymatic.core.service.LogLevel
import io.github.m1n1m1.easymatic.core.service.PromptAnswer
import io.github.m1n1m1.easymatic.core.service.PromptFieldKind
import io.github.m1n1m1.easymatic.core.service.PromptRequest
import io.github.m1n1m1.easymatic.domain.model.config.ValueType
import io.github.m1n1m1.easymatic.engine.ExecutionContext
import io.github.m1n1m1.easymatic.engine.ExecutionRoute
import kotlinx.coroutines.withTimeoutOrNull

/**
 * What the four dialog nodes share: how long they wait, how an answer becomes a
 * branch, and what they need to be shown at all.
 *
 * Written once because the interesting decisions here are decisions about the
 * *family* — an unanswerable question must take the same branch everywhere, or
 * "the dialog was refused" would mean something different on each node.
 */

/**
 * Permission to draw over other apps, declared by every dialog node.
 *
 * The engine runs in a background service, so a window it opens has to survive
 * whatever app the user is actually looking at. There is no lesser grant that
 * would do: starting an Activity from the background is blocked from Android 10
 * on, and holding this permission is precisely the documented exemption — so an
 * Activity would need the same grant and buy nothing.
 */
internal val OVERLAY_PERMISSION = PermissionRequirement(
    manifestPermission = null,
    type = PrerequisiteType.OVERLAY,
    rationaleKey = "overlay.dialog",
)

/**
 * Shows [request] and waits, giving up after [timeoutSeconds] — where zero means
 * "wait as long as it takes". Returns null when the time ran out.
 *
 * The timeout lives here rather than in [io.github.m1n1m1.easymatic.core.service.Prompts]
 * so the renderer has one job. Cancelling this coroutine is what takes the window
 * down, which is also how a macro being disabled mid-question dismisses it.
 */
internal suspend fun ExecutionContext.askUser(request: PromptRequest, timeoutSeconds: Int): PromptAnswer? =
    if (timeoutSeconds <= 0) {
        prompts.ask(request)
    } else {
        withTimeoutOrNull(timeoutSeconds.toLong() * MILLIS_PER_SECOND) { prompts.ask(request) }
    }

/**
 * The branch [answer] takes, logging what happened on the way.
 *
 * [confirmed] and [cancelled] are parameters because `action.dialog_message` has
 * only one continuation: it routes both to `out`, since a message that was read
 * and a message that was dismissed are the same event.
 *
 * [PromptAnswer.Unavailable] takes the *cancelled* branch — failing closed, the
 * same stance an unresolvable contact takes in `PhoneRef`: a question that could
 * not be put must never be answered with a yes nobody gave. It is logged at WARN
 * rather than INFO because, unlike a refusal, it is something the user can fix.
 * Nothing here halts the macro; deciding what an unanswered question means is an
 * `action.if` downstream, which is visible on the canvas.
 */
internal fun ExecutionContext.routeOf(
    answer: PromptAnswer?,
    confirmed: ExecutionRoute,
    cancelled: ExecutionRoute,
): ExecutionRoute = when (answer) {
    null -> {
        log("Nobody answered in time")
        ExecutionRoute.TIMED_OUT
    }
    is PromptAnswer.Confirmed -> {
        log(if (answer.text.isEmpty()) "Confirmed" else "Confirmed with '${answer.text}'")
        confirmed
    }
    PromptAnswer.Cancelled -> {
        log("Cancelled by the user")
        cancelled
    }
    is PromptAnswer.Unavailable -> {
        log("Could not ask: ${answer.reason}. Taking the cancelled branch.", LogLevel.WARN)
        cancelled
    }
}

/**
 * Which control this type is typed into.
 *
 * The one place `ValueType` meets `PromptFieldKind`, and the reason they are two
 * enums: the first says what the answer *is* once it reaches the graph, the second
 * says what the user taps to produce it. [multiline] is only a question for text —
 * a number spread over three lines is not a number.
 */
internal fun ValueType.promptFieldKind(multiline: Boolean): PromptFieldKind = when (this) {
    ValueType.TEXT -> if (multiline) PromptFieldKind.MULTILINE_TEXT else PromptFieldKind.TEXT
    ValueType.NUMBER -> PromptFieldKind.NUMBER
    ValueType.WHOLE_NUMBER -> PromptFieldKind.WHOLE_NUMBER
    ValueType.YES_OR_NO -> PromptFieldKind.YES_OR_NO
    ValueType.DATE_TIME -> PromptFieldKind.DATE_TIME
}

/**
 * The options a choice dialog offers, one per non-blank line.
 *
 * Exactly how `transform.split_text` reads its input, and deliberately so: one
 * `@Wired @Multiline` field is both the literal list (type them) and the dynamic
 * one (wire text in). A list that already exists arrives through
 * `transform.list_join` with a newline separator rather than through a second
 * field that would then have to disagree with this one.
 */
internal fun optionsOf(raw: String): List<String> =
    raw.lines().map { it.trim() }.filter { it.isNotEmpty() }

private const val MILLIS_PER_SECOND = 1_000L
