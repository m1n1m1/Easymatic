package com.example.ottomatic.core.trigger

import com.example.ottomatic.core.model.NodeId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.onSubscription

/**
 * Identifies which Android source produced a [TriggerEvent].
 * Triggers filter the bus by this tag plus their node id.
 */
enum class TriggerSource {
    MANUAL,
    SCHEDULE,
    SMS,
    NOTIFICATION,
    BATTERY,
    BOOT,
    GEOFENCE,
    MACRO,
    APP,
    VARIABLE,
    CONNECTIVITY,
    HARDWARE,
    DISPLAY,
    SYSTEM,
    PACKAGE,
    MEDIA,
}

/**
 * Payload produced when a trigger fires. Pushed into [TriggerBus] by
 * receivers/workers/services in `data/`, and consumed by [Trigger]
 * implementations in `engine/`.
 */
data class TriggerEvent(
    val source: TriggerSource,
    val triggerNodeId: NodeId,
    val payload: Map<String, String> = emptyMap(),
    val firedAtEpochMs: Long = System.currentTimeMillis(),
)

/**
 * Process-wide bus connecting Android system callbacks to the workflow engine.
 *
 * Receivers, workers and services call [emit] or [emitOrHold]; trigger
 * implementations subscribe to [events] or [eventsFor] and filter by source/node.
 *
 * replay = 0 so a freshly-subscribed trigger does not replay stale events. That
 * is a real requirement rather than a default: a geofence-place edit re-arms
 * every macro, so a replaying bus would re-fire every fence on every edit.
 * [emitOrHold] is how the one case replay would have covered is covered instead.
 */
object TriggerBus {

    private val _events = MutableSharedFlow<TriggerEvent>(
        replay = 0,
        extraBufferCapacity = DEFAULT_BUFFER,
    )

    val events: SharedFlow<TriggerEvent> = _events.asSharedFlow()

    /** Events parked for a node that had nothing collecting. Guarded by [heldLock]. */
    private val heldLock = Any()
    private val held = LinkedHashMap<NodeId, MutableList<TriggerEvent>>()

    /**
     * Delivers [event] to whoever is collecting, and drops it if nobody is.
     *
     * Right for a source that only exists while something is listening to it. A
     * manifest receiver, whose broadcast may be the very thing that started this
     * process, wants [emitOrHold] instead.
     */
    fun emit(event: TriggerEvent) {
        _events.tryEmit(event)
    }

    /**
     * Delivers [event] if anything is collecting, and **holds it for
     * [TriggerEvent.triggerNodeId] if nothing is**.
     *
     * This is the case a manifest receiver hits when its own broadcast spawned the
     * process: `onReceive` runs immediately, the engine is still reading workflows
     * off disk, and with `replay = 0` and no subscribers the value is simply gone.
     * Note that [MutableSharedFlow.tryEmit] returns `true` when it discards on an
     * empty subscriber list — there is nothing to buffer *for* — so the decision
     * has to read [MutableSharedFlow.subscriptionCount] and not the emit result.
     *
     * The two branches are exclusive on purpose: a zero subscription count is the
     * only state in which nothing can have received the live emit, so an event is
     * never both delivered and held, and one transition can never run a macro
     * twice.
     *
     * A held event is stamped with [KEY_HELD], so the trigger that picks it up can
     * say it is acting on something that arrived while it was still waking up.
     */
    fun emitOrHold(event: TriggerEvent) {
        if (_events.subscriptionCount.value > 0) {
            _events.tryEmit(event)
            return
        }
        hold(event)
    }

    /**
     * Live events, preceded by whatever was held for [nodeId].
     *
     * The drain runs inside [onSubscription] — after this collector is registered
     * on the shared flow but before it is handed anything live. That is the exact
     * instant at which "nobody was listening" stops being true, which is why it
     * needs no lock of its own and never has to coordinate with the engine's
     * arming mutex. Draining after arming *returns* would not work: arming only
     * launches the collectors, so it finishes before any of them has subscribed.
     */
    fun eventsFor(nodeId: NodeId): Flow<TriggerEvent> =
        _events.onSubscription { takeHeld(nodeId).forEach { emit(it) } }

    private fun hold(event: TriggerEvent) = synchronized(heldLock) {
        prune()
        val queue = held.getOrPut(event.triggerNodeId) { mutableListOf() }
        queue += event.copy(payload = event.payload + (KEY_HELD to "true"))
        while (queue.size > MAX_HELD_PER_NODE) queue.removeAt(0)
        while (held.size > MAX_HELD_NODES) held.remove(held.keys.first())
    }

    private fun takeHeld(nodeId: NodeId): List<TriggerEvent> = synchronized(heldLock) {
        prune()
        held.remove(nodeId).orEmpty()
    }

    /**
     * Discards everything past [HOLD_MAX_AGE_MS]. Run on the way in *and* on the
     * way out, because the expiry that matters most is the one where the engine
     * never came up at all — that event must be gone when the user opens the app
     * an hour later, not fired at them.
     */
    private fun prune() {
        val cutoff = System.currentTimeMillis() - HOLD_MAX_AGE_MS
        val entries = held.entries.iterator()
        while (entries.hasNext()) {
            val queue = entries.next().value
            queue.removeAll { it.firedAtEpochMs < cutoff }
            if (queue.isEmpty()) entries.remove()
        }
    }

    /** Drops every held event. Test seam; nothing in the app calls it. */
    internal fun clearHeld() = synchronized(heldLock) { held.clear() }

    /** Marks a payload as having waited in [held] rather than arriving live. */
    const val KEY_HELD = "heldWhileStarting"

    private const val DEFAULT_BUFFER = 64

    /**
     * How long a held event stays deliverable.
     *
     * Sized for the worst realistic cold start — process spawn, container init, a
     * workflow list read off disk, then arming — and short enough that what is
     * delivered is still true of where the phone is. A transition older than this
     * is history, not an arrival, and must never start a macro.
     */
    internal const val HOLD_MAX_AGE_MS = 60_000L

    /** Enter-then-exit during one cold start is a real pair, so this is not 1. */
    internal const val MAX_HELD_PER_NODE = 4

    private const val MAX_HELD_NODES = 16
}
