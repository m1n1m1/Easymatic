package io.github.m1n1m1.easymatic.engine

import io.github.m1n1m1.easymatic.core.service.Mail
import io.github.m1n1m1.easymatic.core.service.MailFetch
import io.github.m1n1m1.easymatic.core.service.MailFetchResult
import io.github.m1n1m1.easymatic.core.service.MailMessageData
import io.github.m1n1m1.easymatic.core.service.MailSend
import io.github.m1n1m1.easymatic.core.service.MailSendResult
import io.github.m1n1m1.easymatic.core.service.MailUpdate
import io.github.m1n1m1.easymatic.core.service.MailUpdateResult

/**
 * A recording [Mail] for node tests, in the shape of `RecordingSystemServices`.
 *
 * The interesting half is [sendFailure]. Every mail node's contract is "report the
 * failure and pulse `out`, never throw", and that is the part a test has to be
 * able to force — a real server refusing a password is not something a JVM test
 * can arrange.
 */
class FakeMail(
    var sendFailure: String? = null,
    var fetchFailure: String? = null,
    var updateFailure: String? = null,
    var messages: List<MailMessageData> = emptyList(),
) : Mail {

    val sent = mutableListOf<MailSend>()
    val fetched = mutableListOf<MailFetch>()
    val updated = mutableListOf<MailUpdate>()

    override suspend fun send(request: MailSend): MailSendResult {
        sent += request
        return sendFailure
            ?.let { MailSendResult(sent = false, error = it) }
            ?: MailSendResult(sent = true)
    }

    override suspend fun fetch(request: MailFetch): MailFetchResult {
        fetched += request
        return fetchFailure
            ?.let { MailFetchResult(error = it) }
            ?: MailFetchResult(messages = messages, uidValidity = 9)
    }

    override suspend fun update(request: MailUpdate): MailUpdateResult {
        updated += request
        return updateFailure
            ?.let { MailUpdateResult(changed = false, error = it) }
            ?: MailUpdateResult(changed = true)
    }
}
