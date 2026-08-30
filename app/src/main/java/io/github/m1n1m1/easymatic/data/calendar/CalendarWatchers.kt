package io.github.m1n1m1.easymatic.data.calendar

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import io.github.m1n1m1.easymatic.core.model.NodeId
import io.github.m1n1m1.easymatic.core.service.CalendarLimits
import io.github.m1n1m1.easymatic.core.service.LogLevel
import io.github.m1n1m1.easymatic.core.trigger.TriggerBus
import io.github.m1n1m1.easymatic.core.trigger.TriggerEvent
import io.github.m1n1m1.easymatic.core.trigger.TriggerSource
import io.github.m1n1m1.easymatic.engine.trigger.ScheduleHandle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * One `ContentObserver` over the calendar provider, shared by every armed calendar
 * trigger in the process.
 *
 * **The app's first `ContentObserver`**, and it takes `MailWatchers`' shape rather than
 * `WifiNetworkBridge`'s even though it looks more like the latter — one registration for
 * the whole process, no per-node platform resource. The deciding fact is the grant:
 * registering needs `READ_CALENDAR`, so an unconditional registration built in
 * `ServiceLocator.init` would be taken before the user has granted anything and would
 * never recover without a process restart. Registering on the first arm happens after the
 * Problems panel has already told them to grant it.
 *
 * **Coalesced, and that is not a nicety.** The provider notifies once per row it touches,
 * so one account sync pulling a dozen appointments fires the observer a dozen times. A
 * macro on "calendar changed" would run a dozen times, seconds apart, for one sync.
 *
 * **`TriggerBus.emit` rather than `emitOrHold`.** The observer exists only while something
 * is armed, so there is no broadcast-woke-the-process race for a held event to solve —
 * `WifiNetworkBridge`'s sentence exactly.
 */
class CalendarWatchers(
    context: Context,
    private val scope: CoroutineScope,
) {

    private val appContext = context.applicationContext
    private val lock = Any()
    private val interested = mutableSetOf<NodeId>()
    private var observer: ContentObserver? = null
    private var pending: Job? = null

    /** Registers [nodeId]'s interest, returning the handle that withdraws it. */
    fun arm(nodeId: NodeId, onReport: (String, LogLevel) -> Unit): ScheduleHandle {
        synchronized(lock) {
            interested += nodeId
            if (observer == null) register(onReport)
        }
        return ScheduleHandle { withdraw(nodeId) }
    }

    private fun register(onReport: (String, LogLevel) -> Unit) {
        val created = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) = schedule()
        }
        val registered = runCatching {
            appContext.contentResolver.registerContentObserver(
                CalendarContract.CONTENT_URI,
                // Everything below the root: the provider notifies on the specific event,
                // instance or reminder row that changed, never on the root itself.
                true,
                created,
            )
        }.isSuccess
        if (!registered) {
            // The only way this fails is a missing grant, and it fails *silently* to
            // everything else — the trigger would sit armed for ever and never fire.
            onReport("Easymatic does not have calendar access, so calendar changes cannot be noticed", LogLevel.WARN)
            return
        }
        observer = created
    }

    private fun withdraw(nodeId: NodeId) {
        synchronized(lock) {
            interested -= nodeId
            if (interested.isNotEmpty()) return
            observer?.let { appContext.contentResolver.unregisterContentObserver(it) }
            observer = null
            pending?.cancel()
            pending = null
        }
    }

    /**
     * Coalesces a burst of provider notifications into one event per interested node.
     *
     * The debounce restarts on every notification rather than firing on the first, so a
     * sync that takes four seconds produces one event at the end of it rather than one at
     * the start and a second whenever it happens to pause.
     */
    private fun schedule() {
        synchronized(lock) {
            pending?.cancel()
            pending = scope.launch {
                delay(CalendarLimits.CHANGE_DEBOUNCE_MS)
                val nodes = synchronized(lock) { interested.toList() }
                nodes.forEach { nodeId ->
                    TriggerBus.emit(TriggerEvent(source = TriggerSource.CALENDAR, triggerNodeId = nodeId))
                }
            }
        }
    }
}
