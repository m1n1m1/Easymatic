package io.github.m1n1m1.easymatic.data.mail

import io.github.m1n1m1.easymatic.core.model.NodeId
import io.github.m1n1m1.easymatic.core.service.Mail
import io.github.m1n1m1.easymatic.core.service.MailFetch
import io.github.m1n1m1.easymatic.core.service.MailLimits
import io.github.m1n1m1.easymatic.core.service.MailMessageData
import io.github.m1n1m1.easymatic.core.trigger.TriggerBus
import io.github.m1n1m1.easymatic.core.trigger.TriggerEvent
import io.github.m1n1m1.easymatic.core.trigger.TriggerSource
import io.github.m1n1m1.easymatic.domain.model.MailRef
import io.github.m1n1m1.easymatic.engine.trigger.MailPayload

/**
 * One check of one mailbox on behalf of one trigger node, from asking the server
 * to putting events on the bus.
 *
 * Shared by the WorkManager poll and — once it exists — the IDLE watcher, which is
 * the point of it being its own class rather than a method on either. The two
 * differ only in *when* they run; what counts as new, and what a new message looks
 * like on the bus, must not be two answers.
 */
class MailCheck(
    private val mail: Mail,
    private val seen: MailSeenStore,
) {

    /**
     * Asks for anything newer than [nodeId]'s mark, emits what qualifies, and moves
     * the mark up. Returns the reason to report into that node's console, or null
     * when there is nothing worth saying.
     *
     * The return value is deliberately not the message count: a check that found
     * nothing is the normal case and is not news. What *is* news is a mailbox that
     * renumbered itself, because the user is otherwise owed an explanation for a
     * trigger that went quiet for one round.
     */
    suspend fun run(
        nodeId: String,
        accountId: String,
        folder: String,
        unreadOnly: Boolean,
    ): String? {
        val baseline = seen.baseline(nodeId, folder)
        val result = mail.fetch(
            MailFetch(
                accountId = accountId,
                folder = folder,
                unreadOnly = unreadOnly,
                limit = MailLimits.MAX_FETCH,
                // Zero on the first ever check, which is what makes that pass ask
                // for the newest few rather than the entire mailbox.
                sinceUid = baseline?.lastUid ?: 0,
            ),
        )
        if (result.error.isNotEmpty()) return result.error
        val decision = MailDedup.decide(baseline, result.uidValidity, result.messages.map { it.uid })
        return when (decision) {
            is MailDedup.Decision.Rebaseline -> {
                seen.record(nodeId, folder, decision.next)
                decision.reason
            }
            is MailDedup.Decision.Report -> {
                val fresh = decision.uids.toSet()
                result.messages
                    .filter { it.uid in fresh }
                    .sortedBy { it.uid }
                    .forEach { emit(nodeId, it) }
                // Recorded *after* the emits, so a process killed mid-round reports
                // the same message twice rather than never — a duplicate run is
                // recoverable, a mail that never fired anything is invisible.
                seen.record(nodeId, folder, decision.next)
                null
            }
        }
    }

    /**
     * Puts one message on the bus for [nodeId].
     *
     * `emitOrHold` rather than `emit`: a poll can land while the engine is still
     * starting — WorkManager wakes the process for it — and on a `replay = 0` bus
     * that event would simply be lost. Holding it is what makes "an email arrived
     * while the phone was asleep" still run the macro.
     */
    private fun emit(nodeId: String, message: MailMessageData) {
        TriggerBus.emitOrHold(
            TriggerEvent(
                source = TriggerSource.MAIL,
                triggerNodeId = NodeId(nodeId),
                payload = mapOf(
                    MailPayload.REF to MailRef.format(
                        accountId = message.accountId,
                        folder = message.folder,
                        uidValidity = message.uidValidity,
                        uid = message.uid,
                    ),
                    MailPayload.FROM to message.from,
                    MailPayload.FROM_NAME to message.fromName,
                    MailPayload.TO to message.to,
                    MailPayload.SUBJECT to message.subject,
                    MailPayload.BODY to message.body,
                    MailPayload.BODY_TRUNCATED to message.bodyTruncated.toString(),
                    MailPayload.UNREAD to message.unread.toString(),
                    MailPayload.HAS_ATTACHMENTS to message.hasAttachments.toString(),
                    MailPayload.FOLDER to message.folder,
                    MailPayload.ACCOUNT_ID to message.accountId,
                    MailPayload.RECEIVED_AT to message.receivedAtEpochMs.toString(),
                ),
            ),
        )
    }
}
