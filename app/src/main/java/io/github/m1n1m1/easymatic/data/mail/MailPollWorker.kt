package io.github.m1n1m1.easymatic.data.mail

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * Periodic worker that checks one mailbox for one trigger node.
 *
 * Modelled on [io.github.m1n1m1.easymatic.data.trigger.BatteryLevelWorker], including
 * why it exists at all: WorkManager persists the request across reboots and
 * process death, so a mail trigger keeps firing from a killed app — at the
 * platform's 15-minute floor.
 *
 * It stays enqueued even while an IDLE connection is up, only slowed down. A
 * socket dropped by carrier NAT sits in IDLE *believing it is healthy*, with no
 * error to back off from, and this hourly round trip is the only thing that would
 * ever notice.
 *
 * Keyed by **node** rather than by account, which WorkManager's unique-name model
 * leaves no choice about. Two nodes watching one inbox therefore poll separately;
 * at a 15-minute floor that is a cost worth not engineering around.
 */
class MailPollWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val nodeId = inputData.getString(KEY_NODE_ID)
        val accountId = inputData.getString(KEY_ACCOUNT_ID)
        val mail = MailRuntime.mail
        val seen = MailRuntime.seen
        return when {
            nodeId == null || accountId == null -> Result.failure()
            // Not yet attached means the process is still coming up, which
            // WorkManager is entitled to retry rather than be told work failed.
            mail == null || seen == null -> Result.retry()
            else -> {
                MailCheck(mail, seen).run(
                    nodeId = nodeId,
                    accountId = accountId,
                    folder = inputData.getString(KEY_FOLDER) ?: DEFAULT_FOLDER,
                    unreadOnly = inputData.getBoolean(KEY_UNREAD_ONLY, true),
                )
                // Always success: a server that refused is not work for WorkManager
                // to retry with its own backoff — the next scheduled run *is* the
                // retry, and the reason has already been reported where it is read.
                Result.success()
            }
        }
    }

    companion object {
        const val KEY_NODE_ID = "nodeId"
        const val KEY_ACCOUNT_ID = "accountId"
        const val KEY_FOLDER = "folder"
        const val KEY_UNREAD_ONLY = "unreadOnly"

        const val WORK_NAME_PREFIX = "easymatic_mail_poll_"

        private const val DEFAULT_FOLDER = "INBOX"
    }
}
