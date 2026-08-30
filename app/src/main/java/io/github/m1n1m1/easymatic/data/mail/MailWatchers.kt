package io.github.m1n1m1.easymatic.data.mail

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import io.github.m1n1m1.easymatic.core.model.NodeId
import io.github.m1n1m1.easymatic.core.service.LogLevel
import io.github.m1n1m1.easymatic.data.MailAccountRepository
import io.github.m1n1m1.easymatic.engine.trigger.DEFAULT_MAIL_POLL_MINUTES
import io.github.m1n1m1.easymatic.engine.trigger.MailWatchMode
import io.github.m1n1m1.easymatic.engine.trigger.MailWatchSpec
import io.github.m1n1m1.easymatic.engine.trigger.ScheduleHandle
import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Watches mailboxes on behalf of armed `trigger.mail` nodes.
 *
 * Held by [io.github.m1n1m1.easymatic.data.trigger.AndroidTriggerHost] and constructed
 * in `ServiceLocator`, exactly as `SensorBridge` is — not by `MacroEngineService`,
 * which lives in `engine/` and may not import `data/`.
 *
 * **Two mechanisms, deliberately not two modes.** The substrate is a WorkManager
 * poll, which is what makes this survive process death and reboot. [MailIdleWatcher]
 * is layered on top when the server offers IDLE, cutting latency from minutes to
 * seconds — and the poll is not torn down when it comes up, only **slowed** to
 * [IDLE_BACKSTOP_MINUTES]. That is the belt-and-braces call and it earns its keep:
 * a socket dropped by carrier NAT sits parked in IDLE *believing it is healthy*,
 * with no error to back off from, and an hourly authenticated round trip is the
 * only thing that would ever notice. It also means nothing has to be *started*
 * when IDLE dies.
 *
 * Interest is reference-counted per **mailbox**, not per node: several trigger
 * nodes may watch one inbox and must cost one connection between them — the
 * contract `SensorBridge` states for a single sensor registration shared by every
 * subscriber. The poll stays per node, because WorkManager's unique-name model
 * leaves no choice and a 15-minute floor makes the duplication cheap.
 *
 * Teardown has one rule that is not obvious and is load-bearing:
 * `MacroEngineService.arm()` cancels **and joins** the previous runner, and a
 * trigger's `finally { handle.cancel() }` runs inside that cancellation — so a
 * cancel must be effective by the time it returns, or it lands on top of whatever
 * the next arm just registered.
 */
@Suppress("TooManyFunctions") // Two mechanisms plus their bookkeeping; splitting them would only hide the seam.
class MailWatchers(
    context: Context,
    private val accounts: MailAccountRepository? = null,
) {

    private val workManager = WorkManager.getInstance(context.applicationContext)

    private val lock = Any()

    private val watches = mutableMapOf<String, Watch>()

    /**
     * The handler is the part that matters, for `ServiceLocator.appScope`'s stated
     * reason: a [SupervisorJob] stops one child cancelling its siblings and does
     * nothing at all about the exception, which would otherwise reach the thread's
     * default handler and take the process down over a dropped socket.
     */
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, error ->
            android.util.Log.e("Easymatic", "Mail watcher failed", error)
        },
    )

    /** One node's interest in a mailbox, and where to report back to. */
    private class Watcher(val spec: MailWatchSpec, val report: (String, LogLevel) -> Unit)

    /** One mailbox, and everything watching it. */
    private class Watch(val accountId: String, val folder: String) {
        val watchers = mutableMapOf<NodeId, Watcher>()
        var job: Job? = null
        var idle: MailIdleWatcher? = null

        /** Whether a connection is currently believed to be delivering pushes. */
        var pushing = false
    }

    /**
     * Registers [nodeId]'s interest in [accountId] and starts checking.
     *
     * The returned handle withdraws that interest. It removes the mailbox's entry
     * from the map **before** doing anything slower, so a subsequent arm builds a
     * fresh entry this teardown can no longer reach — the guarantee
     * `activeJobs.remove(workflowId, job)`'s two-argument form gives
     * `MacroEngineService`, and the same failure (a stale close landing on a new
     * registration) it prevents.
     */
    fun arm(
        nodeId: NodeId,
        accountId: String,
        spec: MailWatchSpec,
        onReport: (String, LogLevel) -> Unit = { _, _ -> },
    ): ScheduleHandle {
        val key = key(accountId, spec.folder)
        val pushing = synchronized(lock) {
            val watch = watches.getOrPut(key) { Watch(accountId, spec.folder) }
            watch.watchers[nodeId] = Watcher(spec, onReport)
            if (watch.job == null && spec.mode == MailWatchMode.AUTOMATIC && accounts != null) {
                watch.job = scope.launch { hold(key) }
            }
            watch.pushing
        }
        armPoll(nodeId, accountId, spec, demoted = pushing)
        // A PeriodicWorkRequest does not run when it is enqueued — it runs at the
        // end of its first interval. Without this, arming leaves a fifteen-minute
        // window in which nothing has established where this node has read up to,
        // and the first poll then *baselines* over everything that arrived in it
        // rather than reporting it. Sending yourself a test mail immediately after
        // switching a macro on is exactly that window, so the one case everybody
        // tries first was the one case that silently did nothing.
        kickCheck(nodeId, accountId, spec)
        return ScheduleHandle { disarm(nodeId, key) }
    }

    private fun disarm(nodeId: NodeId, key: String) {
        val closing = synchronized(lock) {
            val watch = watches[key] ?: return@synchronized null
            watch.watchers.remove(nodeId)
            if (watch.watchers.isNotEmpty()) return@synchronized null
            // Removed before the connection is touched, so a re-arm arriving now
            // builds a fresh Watch that this teardown cannot reach.
            watches.remove(key)
            watch
        }
        workManager.cancelUniqueWork(workName(nodeId))
        workManager.cancelUniqueWork(kickName(nodeId))
        // Closing the socket is what unblocks a thread parked in idle(); the job is
        // cancelled but deliberately not joined, since joining would block a
        // `finally` inside a cancellation for as long as a close takes.
        closing?.idle?.disconnect()
        closing?.job?.cancel()
    }

    // ---- The held-open connection ------------------------------------------

    /**
     * Keeps a connection up for one mailbox, reconnecting with backoff, and gives
     * up on IDLE after [MAX_IDLE_FAILURES] consecutive failures.
     *
     * Giving up is never permanent: networks change, and a user who walked off a
     * hostile Wi-Fi should get push back without re-arming anything. So it waits
     * [IDLE_RETRY_AFTER_MS] and tries again, with the poll restored to its
     * configured interval in the meantime.
     */
    private suspend fun hold(key: String) {
        var failures = 0
        var backoffMs = INITIAL_BACKOFF_MS
        while (currentCoroutineContext().isActive) {
            val startedAt = System.currentTimeMillis()
            val outcome = runCatching { connectAndHold(key) }
            setPushing(key, false)
            if (outcome.isSuccess) return // No IDLE on this server; the poll is the answer.
            // Reset on a connection that *lasted*, not on one that merely opened:
            // a server that accepts and immediately drops would otherwise look
            // healthy and produce a hot reconnect loop.
            if (System.currentTimeMillis() - startedAt > HEALTHY_MS) {
                failures = 0
                backoffMs = INITIAL_BACKOFF_MS
            }
            // The first drop of a streak is reported with the server's own words.
            // Staying silent until the third one meant several minutes in which
            // push was plainly not working and the console said nothing about it —
            // which is the state this whole reporting channel exists to prevent.
            if (failures == 0) {
                val reason = outcome.exceptionOrNull()?.let(MailTransport::explain).orEmpty()
                report(key, "Push connection dropped ($reason). Reconnecting.", LogLevel.DEBUG)
            }
            failures++
            if (failures >= MAX_IDLE_FAILURES) {
                report(
                    key,
                    "Push delivery keeps dropping on this connection, so Easymatic has fallen back " +
                        "to checking on a schedule. It will try push again in an hour.",
                    LogLevel.WARN,
                )
                delay(IDLE_RETRY_AFTER_MS)
                failures = 0
                backoffMs = INITIAL_BACKOFF_MS
            } else {
                delay(backoffMs)
                backoffMs = min(backoffMs * 2, MAX_BACKOFF_MS)
            }
        }
    }

    private suspend fun connectAndHold(key: String) {
        val watch = snapshot(key)
        val account = watch?.let { accounts?.get(it.accountId) }
        // A deleted account or an unreadable password is not a connection failure
        // to back off from: the trigger already reported it at arm time, and the
        // poll will report it again on its own schedule.
        val password = account?.let { accounts?.password(watch.accountId) } ?: return
        val idle = MailIdleWatcher(account, password, watch.folder)
        synchronized(lock) { watches[key]?.idle = idle }
        idle.run(
            scope = scope,
            onOpen = {
                setPushing(key, true)
                report(key, "Push is on: new mail will start this macro within seconds.")
                // A connection only announces mail that arrives *while it is open*,
                // so everything delivered during the gap — a reconnect, a tunnel, a
                // night in doze — would otherwise wait for the hourly backstop.
                scope.launch { check(key) }
            },
            onUnsupported = {
                report(key, "This mail server does not support push, so Easymatic checks on a schedule instead.")
            },
            onArrival = { check(key) },
        )
    }

    /** Runs a check for every node watching this mailbox, each with its own mark. */
    private suspend fun check(key: String) {
        val mail = MailRuntime.mail
        val seen = MailRuntime.seen
        val watch = snapshot(key)
        if (mail == null || seen == null || watch == null) return
        val watchers = synchronized(lock) { watches[key]?.watchers?.toMap() }.orEmpty()
        watchers.forEach { (nodeId, watcher) ->
            val reason = MailCheck(mail, seen).run(
                nodeId = nodeId.value,
                accountId = watch.accountId,
                folder = watch.folder,
                unreadOnly = watcher.spec.unreadOnly,
            )
            reason?.let { watcher.report(it, LogLevel.WARN) }
        }
    }

    // ---- Bookkeeping --------------------------------------------------------

    private fun snapshot(key: String): Watch? = synchronized(lock) { watches[key] }

    private fun report(key: String, message: String, level: LogLevel = LogLevel.INFO) {
        val watchers = synchronized(lock) { watches[key]?.watchers?.values?.toList() }.orEmpty()
        watchers.forEach { it.report(message, level) }
    }

    /**
     * Records whether push is live and re-arms the polls to match.
     *
     * The poll is never cancelled here, only moved between its configured interval
     * and the hourly backstop — which is what makes a dead IDLE a non-event rather
     * than something that has to be detected.
     */
    private fun setPushing(key: String, pushing: Boolean) {
        val (accountId, watchers) = synchronized(lock) {
            val watch = watches[key] ?: return
            if (watch.pushing == pushing) return
            watch.pushing = pushing
            watch.accountId to watch.watchers.toMap()
        }
        watchers.forEach { (nodeId, watcher) -> armPoll(nodeId, accountId, watcher.spec, demoted = pushing) }
    }

    // ---- The poll underneath -------------------------------------------------

    private fun armPoll(nodeId: NodeId, accountId: String, spec: MailWatchSpec, demoted: Boolean) {
        val minutes = if (demoted) {
            maxOf(spec.intervalMinutes, IDLE_BACKSTOP_MINUTES)
        } else {
            spec.intervalMinutes.coerceAtLeast(DEFAULT_MAIL_POLL_MINUTES)
        }
        val request = PeriodicWorkRequestBuilder<MailPollWorker>(minutes, TimeUnit.MINUTES)
            .setInputData(inputFor(nodeId, accountId, spec))
            // The one departure from `armBatteryLevelPoll`: a mail check with no
            // network is a socket guaranteed to fail, and WorkManager will re-run
            // it the moment connectivity returns — cheaper and more timely than
            // burning the slot on a failure.
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

    /**
     * One check, now, so the node knows where it has read up to before the first
     * periodic run comes round.
     *
     * A separate unique name from the periodic work because WorkManager keeps the
     * two kinds in different namespaces — `enqueueUniqueWork` and
     * `enqueueUniquePeriodicWork` will not manage each other's requests.
     */
    private fun kickCheck(nodeId: NodeId, accountId: String, spec: MailWatchSpec) {
        val request = OneTimeWorkRequestBuilder<MailPollWorker>()
            .setInputData(inputFor(nodeId, accountId, spec))
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
            )
            .build()
        workManager.enqueueUniqueWork(kickName(nodeId), ExistingWorkPolicy.REPLACE, request)
    }

    private fun inputFor(nodeId: NodeId, accountId: String, spec: MailWatchSpec) = workDataOf(
        MailPollWorker.KEY_NODE_ID to nodeId.value,
        MailPollWorker.KEY_ACCOUNT_ID to accountId,
        MailPollWorker.KEY_FOLDER to spec.folder,
        MailPollWorker.KEY_UNREAD_ONLY to spec.unreadOnly,
    )

    private fun workName(nodeId: NodeId) = MailPollWorker.WORK_NAME_PREFIX + nodeId.value

    private fun kickName(nodeId: NodeId) = MailPollWorker.WORK_NAME_PREFIX + "now_" + nodeId.value

    // A connection selects exactly one folder, so two nodes on two folders of one
    // account are two connections — which is what IMAP requires, not a choice.
    private fun key(accountId: String, folder: String) = "$accountId $folder"

    private companion object {
        /** How often the poll still runs while push is believed to be working. */
        const val IDLE_BACKSTOP_MINUTES = 60L

        const val MAX_IDLE_FAILURES = 3
        const val INITIAL_BACKOFF_MS = 30_000L
        const val MAX_BACKOFF_MS = 30L * 60 * 1000
        const val IDLE_RETRY_AFTER_MS = 60L * 60 * 1000

        /** A connection that lasted this long counts as having worked. */
        const val HEALTHY_MS = 60_000L
    }
}
