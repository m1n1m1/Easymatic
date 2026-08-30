package io.github.m1n1m1.easymatic.engine

import io.github.m1n1m1.easymatic.core.service.NotificationAnswer
import io.github.m1n1m1.easymatic.core.service.NotificationRequest
import io.github.m1n1m1.easymatic.core.service.Notifications
import kotlinx.coroutines.CompletableDeferred

/**
 * Recording [Notifications] fake: every posted notification is captured, and the
 * answer a test wants handed back is queued up front.
 *
 * [RecordingSystemServices]' shape with one addition it does not need — this facade
 * *waits*, so a test has to be able to say both "somebody pressed the second button"
 * and "nobody ever did". The second is the case worth having a fake for at all: it is
 * how the "an ignored notification pulses nothing" contract gets tested without a
 * device and without a real clock.
 */
class RecordingNotifications(
    /** What [await] hands back. Null means the notification went away unanswered. */
    private val answer: NotificationAnswer? = null,
    /** When true, [await] never returns rather than answering — a notification still on screen. */
    private val neverAnswers: Boolean = false,
) : Notifications {

    /** One entry per [post], in order. */
    val posted = mutableListOf<NotificationRequest>()

    /**
     * Title and text of each posted notification.
     *
     * `action.notify` is the probe half the executor tests observe through — it is
     * the shortest node that says something checkable — and none of them cares about
     * the other eleven fields. This keeps those assertions reading as they did when
     * the whole facade was two strings.
     */
    val titlesAndTexts: List<Pair<String, String>> get() = posted.map { it.title to it.text }

    /** One entry per [cancel], whether or not anything was showing. */
    val cancelled = mutableListOf<String>()

    /** Tags [cancel] reports as having been showing. Everything else answers false. */
    val showing = mutableSetOf<String>()

    /** How long the last [await] was given, so a test can pin the node's timeout arithmetic. */
    var awaitedForMs: Long? = null
        private set

    /**
     * When true, [post] records the request but answers null — what a phone with
     * notifications switched off does. The request is still recorded, so a test can
     * assert the node built it correctly *and* handled the refusal.
     */
    var postFails: Boolean = false

    override suspend fun post(request: NotificationRequest): Long? {
        posted += request
        return if (postFails) null else posted.size.toLong()
    }

    override suspend fun await(token: Long, timeoutMs: Long): NotificationAnswer? {
        awaitedForMs = timeoutMs
        // A never-completing Deferred rather than a plain `delay`, so a test asserting
        // "this branch does not run" does not depend on how long it is willing to wait.
        if (neverAnswers) CompletableDeferred<Unit>().await()
        return answer
    }

    override fun cancel(tag: String): Boolean {
        cancelled += tag
        return showing.remove(tag)
    }
}
