package io.github.m1n1m1.easymatic.data.mail

import io.github.m1n1m1.easymatic.core.service.Mail
import io.github.m1n1m1.easymatic.core.service.MailFetch
import io.github.m1n1m1.easymatic.core.service.MailFetchResult
import io.github.m1n1m1.easymatic.core.service.MailSend
import io.github.m1n1m1.easymatic.core.service.MailSendResult
import io.github.m1n1m1.easymatic.core.service.MailUpdate
import io.github.m1n1m1.easymatic.core.service.MailUpdateResult
import io.github.m1n1m1.easymatic.data.MailAccountRepository
import io.github.m1n1m1.easymatic.domain.model.MailAccount
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [Mail] over [MailTransport], resolving accounts through the library.
 *
 * This is the class that keeps the facade's promise: **nothing leaves here as an
 * exception**. A missing account, a password this device can no longer open, a
 * refused sign-in, a dead network and a malformed recipient are all results
 * carrying an `error`, in the same way `SystemServices.httpRequest` answers `-1`
 * for a request that never happened. A node downstream sees one shape whatever
 * went wrong, which is what lets `action.if` branch on `sent` rather than on
 * whether the macro is still running.
 *
 * The dispatcher is owned here rather than at each call site, which is the reason
 * [Mail] is suspending where [io.github.m1n1m1.easymatic.core.service.SystemServices]
 * is not: four things reach this — two actions, a poll worker and a long-lived
 * watcher — and only one of them is an action written by somebody thinking about
 * threads.
 */
class AndroidMail(
    private val accounts: MailAccountRepository,
) : Mail {

    override suspend fun send(request: MailSend): MailSendResult {
        val resolved = resolve(request.accountId)
        return when (resolved) {
            is Resolved.Missing -> MailSendResult(sent = false, error = resolved.reason)
            is Resolved.Ready -> withContext(Dispatchers.IO) {
                runCatching { MailTransport.sendMessage(resolved.account, resolved.password, request) }
                    .fold(
                        onSuccess = { MailSendResult(sent = true) },
                        onFailure = { MailSendResult(sent = false, error = MailTransport.explain(it)) },
                    )
            }
        }
    }

    override suspend fun fetch(request: MailFetch): MailFetchResult {
        val resolved = resolve(request.accountId)
        return when (resolved) {
            is Resolved.Missing -> MailFetchResult(error = resolved.reason)
            is Resolved.Ready -> withContext(Dispatchers.IO) {
                runCatching { MailTransport.listMessages(resolved.account, resolved.password, request) }
                    .getOrElse { MailFetchResult(error = MailTransport.explain(it)) }
            }
        }
    }

    override suspend fun update(request: MailUpdate): MailUpdateResult {
        val resolved = resolve(request.accountId)
        return when (resolved) {
            is Resolved.Missing -> MailUpdateResult(changed = false, error = resolved.reason)
            is Resolved.Ready -> withContext(Dispatchers.IO) {
                runCatching { MailTransport.applyOp(resolved.account, resolved.password, request) }
                    .fold(
                        onSuccess = { changed ->
                            MailUpdateResult(
                                changed = changed,
                                error = if (changed) "" else GONE,
                            )
                        },
                        onFailure = { MailUpdateResult(changed = false, error = MailTransport.explain(it)) },
                    )
            }
        }
    }

    /**
     * An account and a password, or the reason there is not one.
     *
     * Two failures worth telling apart, because they are fixed in different places:
     * the account is gone (deleted, or never chosen) and the password cannot be
     * read (restored onto a phone whose keystore never had the key).
     */
    private sealed interface Resolved {
        data class Ready(val account: MailAccount, val password: String) : Resolved
        data class Missing(val reason: String) : Resolved
    }

    private fun resolve(accountId: String): Resolved {
        val account = accounts.get(accountId)
        return when {
            accountId.isBlank() -> Resolved.Missing("No mail account chosen")
            account == null -> Resolved.Missing("That mail account has been deleted")
            else -> accounts.password(accountId)
                ?.let { password -> Resolved.Ready(account, password) }
                ?: Resolved.Missing(
                    "The password for \"${account.name}\" could not be read. " +
                        "Open Mail accounts and type it again.",
                )
        }
    }

    private companion object {
        const val GONE = "That message is no longer in this mailbox — it may have been moved or deleted"
    }
}
