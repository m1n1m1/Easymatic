package com.example.ottomatic.data.mail

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.example.ottomatic.core.model.NodeId
import com.example.ottomatic.engine.trigger.DEFAULT_MAIL_POLL_MINUTES
import com.example.ottomatic.engine.trigger.MailWatchSpec
import com.example.ottomatic.engine.trigger.ScheduleHandle
import java.util.concurrent.TimeUnit

/**
 * Watches mailboxes on behalf of armed `trigger.mail` nodes.
 *
 * Held by [com.example.ottomatic.data.trigger.AndroidTriggerHost] and constructed
 * in `ServiceLocator`, exactly as `SensorBridge` is — not by `MacroEngineService`,
 * which lives in `engine/` and may not import `data/`.
 *
 * The substrate is a **WorkManager poll**, which is what makes this survive
 * process death and reboot. Interest is reference-counted per **account** rather
 * than per node, because several trigger nodes may watch one inbox and must cost
 * one connection between them once IDLE is layered on — the contract `SensorBridge`
 * states for a single sensor registration shared by every subscriber.
 *
 * Teardown has one rule that is not obvious and is load-bearing:
 * `MacroEngineService.arm()` cancels **and joins** the previous runner, and a
 * trigger's `finally { handle.cancel() }` runs inside that cancellation — so a
 * cancel must be effective by the time it returns, or it lands on top of whatever
 * the *next* arm just registered.
 */
class MailWatchers(
    context: Context,
) {

    private val workManager = WorkManager.getInstance(context.applicationContext)

    private val lock = Any()

    /** Which nodes are watching each account, and with what. */
    private val interested = mutableMapOf<String, MutableMap<NodeId, MailWatchSpec>>()

    /**
     * Registers [nodeId]'s interest in [accountId] and starts checking.
     *
     * The returned handle withdraws that interest. It removes the account's entry
     * from the map **before** doing anything slower, so a subsequent arm builds a
     * fresh entry this teardown can no longer reach — the guarantee
     * `activeJobs.remove(workflowId, job)`'s two-argument form gives
     * `MacroEngineService`, and the same failure (a stale close landing on a new
     * registration) it prevents.
     */
    fun arm(nodeId: NodeId, accountId: String, spec: MailWatchSpec): ScheduleHandle {
        synchronized(lock) {
            interested.getOrPut(accountId) { mutableMapOf() }[nodeId] = spec
        }
        armPoll(nodeId, accountId, spec)
        return ScheduleHandle {
            synchronized(lock) {
                val watchers = interested[accountId]
                watchers?.remove(nodeId)
                if (watchers != null && watchers.isEmpty()) interested.remove(accountId)
            }
            workManager.cancelUniqueWork(workName(nodeId))
        }
    }

    private fun armPoll(nodeId: NodeId, accountId: String, spec: MailWatchSpec) {
        val minutes = spec.intervalMinutes.coerceAtLeast(DEFAULT_MAIL_POLL_MINUTES)
        val request = PeriodicWorkRequestBuilder<MailPollWorker>(minutes, TimeUnit.MINUTES)
            .setInputData(
                workDataOf(
                    MailPollWorker.KEY_NODE_ID to nodeId.value,
                    MailPollWorker.KEY_ACCOUNT_ID to accountId,
                    MailPollWorker.KEY_FOLDER to spec.folder,
                    MailPollWorker.KEY_UNREAD_ONLY to spec.unreadOnly,
                ),
            )
            // The one departure from `armBatteryLevelPoll`: a mail check with no
            // network is a socket guaranteed to fail, and WorkManager will re-run
            // it the moment connectivity returns — which is both cheaper and more
            // timely than burning the slot on a failure.
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
            )
            .build()
        workManager.enqueueUniquePeriodicWork(
            workName(nodeId),
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    private fun workName(nodeId: NodeId) = MailPollWorker.WORK_NAME_PREFIX + nodeId.value
}
