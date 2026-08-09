package com.example.ottomatic.data.wait

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import com.example.ottomatic.core.service.DelayWaits
import com.example.ottomatic.core.service.Waits
import com.example.ottomatic.data.trigger.setWakeup
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Android [Waits]: a one-shot `RTC_WAKEUP` alarm that fires through doze, with a
 * plain wall-clock sleep racing it as a safety net.
 *
 * ### Why a token rather than a node id
 *
 * [com.example.ottomatic.data.trigger.AndroidTriggerHost.armAlarm] keys its
 * `PendingIntent` on the trigger's node id, which is right there: one armed
 * trigger, one pending alarm. A *wait* has no such guarantee. The executor mints
 * a run id per call precisely because two runs of one workflow genuinely overlap,
 * and a wait inside a loop body multiplies that further — so two waits on the
 * same node are ordinary, and keying on the node would collapse them onto one
 * `PendingIntent` under `FLAG_UPDATE_CURRENT`, firing only the last. Each wait
 * therefore takes its own token, and this class keeps its own receiver rather
 * than borrowing `AlarmReceiver`, whose events go onto the trigger bus keyed by
 * node id where a `trigger.schedule` node with the same id would read them.
 *
 * ### Why the fallback race
 *
 * The alarm can be dropped — an aggressive OEM power manager, a `PendingIntent`
 * the system decided to garbage-collect. [DelayWaits] can only ever fire *late*,
 * never early, so racing the two and taking whichever arrives first is always
 * correct: the alarm wins under doze, the timer wins when the alarm never comes.
 *
 * ### Why the wake lock wraps the caller's block
 *
 * `onReceive` runs under a system wake lock that is released the moment it
 * returns, which is long before a resumed coroutine is scheduled — never mind
 * the graph walk that follows it. Holding the lock across [Waits.awaitUntil]'s
 * block is what keeps the device up for the work the wait was for.
 */
class AndroidWaits(context: Context) : Waits {

    private val appContext = context.applicationContext
    private val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    private val powerManager = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager

    override suspend fun <T> awaitUntil(atEpochMs: Long, thenDo: suspend () -> T): T {
        val remaining = atEpochMs - System.currentTimeMillis()
        // Short enough that the device is very unlikely to suspend across it, and
        // arming an alarm costs more than the accuracy it would buy.
        if (remaining < SHORT_WAIT_MS) return DelayWaits.awaitUntil(atEpochMs, thenDo)

        val token = tokens.incrementAndGet()
        val fired = CompletableDeferred<Unit>()
        val pendingIntent = alarmPendingIntent(token)
        pending[token] = fired
        try {
            alarmManager.setWakeup(atEpochMs, pendingIntent, "wait $token")
            coroutineScope {
                val fallback = launch { DelayWaits.awaitUntil(atEpochMs) { fired.complete(Unit) } }
                fired.await()
                fallback.cancel()
            }
        } finally {
            // Also the cancellation path: a disarmed macro must leave no alarm
            // behind to wake the device for a run that will never happen.
            pending.remove(token)
            alarmManager.cancel(pendingIntent)
        }
        return awake(thenDo)
    }

    /** Runs [block] with the CPU held awake, however long the device wanted to sleep. */
    private suspend fun <T> awake(block: suspend () -> T): T {
        val lock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
        // Timed, so a branch that hangs — an HTTP call with no timeout, a dialog
        // nobody answers — cannot pin the CPU on for the rest of the day.
        lock.acquire(MAX_AWAKE_MS)
        return try {
            block()
        } finally {
            if (lock.isHeld) lock.release()
        }
    }

    private fun alarmPendingIntent(token: Long): PendingIntent {
        val intent = Intent(appContext, WaitAlarmReceiver::class.java).apply {
            action = WaitAlarmReceiver.ACTION_WAIT
            putExtra(WaitAlarmReceiver.EXTRA_TOKEN, token)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(appContext, token.toInt(), intent, flags)
    }

    internal companion object {

        /** Below this, a plain sleep is used and no alarm is armed. */
        private const val SHORT_WAIT_MS = 60_000L

        /** Upper bound on how long one resumed branch may hold the CPU awake. */
        private const val MAX_AWAKE_MS = 60_000L

        private const val WAKE_LOCK_TAG = "Ottomatic:wait"

        private val tokens = AtomicLong()

        /**
         * Waits currently armed, by token.
         *
         * Process-wide rather than per-instance because the receiver is
         * constructed by the system and has no route to whichever [AndroidWaits]
         * armed the alarm. In-memory, which is the limitation stated on [Waits]:
         * an alarm that outlives the process arrives to an empty map and resumes
         * nothing.
         */
        private val pending = ConcurrentHashMap<Long, CompletableDeferred<Unit>>()

        /** Resumes the wait [token] identifies, if it is still armed. */
        fun release(token: Long) {
            pending.remove(token)?.complete(Unit)
        }
    }
}
