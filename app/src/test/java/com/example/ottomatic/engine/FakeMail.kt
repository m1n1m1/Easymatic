package com.example.ottomatic.engine

import com.example.ottomatic.core.service.Mail
import com.example.ottomatic.core.service.MailFetch
import com.example.ottomatic.core.service.MailFetchResult
import com.example.ottomatic.core.service.MailSend
import com.example.ottomatic.core.service.MailSendResult
import com.example.ottomatic.core.service.MailUpdate
import com.example.ottomatic.core.service.MailUpdateResult

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
) : Mail {

    val sent = mutableListOf<MailSend>()

    override suspend fun send(request: MailSend): MailSendResult {
        sent += request
        return sendFailure
            ?.let { MailSendResult(sent = false, error = it) }
            ?: MailSendResult(sent = true)
    }

    override suspend fun fetch(request: MailFetch): MailFetchResult = MailFetchResult()

    override suspend fun update(request: MailUpdate): MailUpdateResult = MailUpdateResult(changed = true)
}
