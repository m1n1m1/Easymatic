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

    /**
     * A message read out of a messenger's notification.
     *
     * Separate from [NOTIFICATION] rather than a filter over it, because the two
     * carry different payloads and one post can produce both: `trigger.notification`
     * still sees every notification exactly as it always did, and only a post that
     * reads as a message produces this as well.
     */
    MESSAGE,
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

    /**
     * A picture appearing in the phone's media collection.
     *
     * **Not [MEDIA]**, which is media *buttons* and card mounts, and the difference is
     * the one [HOME_ASSISTANT]'s KDoc draws rather than [MESSAGE]'s. Those are broadcasts
     * addressed to every armed node, which then filter; this is routed to **one node id**
     * by a registration table in `data/`, because each node carries its own high-water
     * mark and one photo is new to some armed triggers and not to others. Sharing a
     * source would wake every image trigger in the process to run a filter chain over a
     * picture another node had already claimed.
     *
     * A future `trigger.video_saved` shares this source rather than adding another: the
     * payload carries which kind it is, and the observer, the diff and the mark are the
     * same machinery.
     */
    MEDIA_STORE,
    NFC,
    MAIL,

    /**
     * Something on a Home Assistant hub — an entity's state, or an event on its bus.
     *
     * **One source for both triggers**, unlike [MESSAGE] and [NOTIFICATION], and the
     * difference is worth stating because the two cases look alike. There, the filtering
     * happens in `engine/`: both triggers read the same bus, so they need to be able to
     * tell one arrival from another. Here the `data/` side holds a registration table
     * and addresses each event to **one node id** — a busy install emits hundreds of
     * `state_changed` a minute, and broadcasting them would wake every armed trigger in
     * the process to run its own filter chain. With the routing already done, a second
     * source would distinguish nothing that the node id does not.
     *
     * A `state_changed` is also an ordinary event on the bus, so a `trigger.ha_event`
     * watching that type still fires alongside a `trigger.ha_state` on the same entity.
     * That is deliberate and is what having two nodes means.
     */
    HOME_ASSISTANT,

    /**
     * A message on a topic somebody's MQTT broker published.
     *
     * [HOME_ASSISTANT]'s argument, arrived at the same way and with one extra reason to
     * be its own source rather than folded into it. The `data/` side holds the
     * registration table and addresses each message to **one node id**, because a broker
     * publishes constantly and broadcasting would wake every armed trigger in the process
     * to run its own filter chain.
     *
     * It is not a member of [HOME_ASSISTANT] even though a great many of these messages
     * originate in a Home Assistant install: what distinguishes the two is not where the
     * message came from but **what carries it**, and the payload shapes have nothing in
     * common — an entity id and a state on one side, a topic and opaque bytes on the
     * other.
     */
    MQTT,

    /**
     * Something in the phone's calendars changed.
     *
     * Addressed to one node rather than broadcast, on [HOME_ASSISTANT]'s rule and for a
     * sharper version of its reason: a single account sync fires the provider's observer
     * once per row it touched, so waking every armed trigger in the process to run its own
     * filter chain would turn one background sync into dozens of pointless wake-ups.
     * `CalendarWatchers` holds the registration table and emits one event per interested
     * node.
     *
     * **A calendar trigger's *alarm* does not come through here.** `armAlarm` already
     * emits [SCHEDULE] keyed by the node id, and a calendar node's alarm reaches only that
     * node, so a second source for it would be a distinction nothing acts on. What arrives
     * on this one is only "the diary changed, re-plan" — see `CalendarEventTrigger`.
     */
    CALENDAR,

    /**
     * A recording this app made finished and was saved.
     *
     * **Broadcast rather than addressed to a node id**, which is the opposite of
     * [MEDIA_STORE] despite the two sounding alike. That one is a ContentObserver whose
     * events are routed by a registration table because each node carries its own
     * high-water mark, so one photo is new to some armed triggers and not to others. There
     * is no observer here and no mark: the recorder in `data/` knows exactly when a
     * recording ended because it ended it, and that fact is equally new to every armed
     * node. The node id on the event is therefore unset and `trigger.recording_saved`
     * filters on the source alone.
     *
     * It also carries the one thing the actions cannot always deliver themselves. A
     * recording started by `action.record_start` ends asynchronously — by its own limit,
     * or by an `action.record_stop` in a different macro — so without this the finished
     * file would have nowhere to arrive.
     */
    RECORDING,

    /**
     * A media player started, paused, stopped or moved to another track.
     *
     * **Not [MEDIA]**, which is media *buttons* and card mounts. That source is a
     * manifest receiver hearing a key press; this one is a `MediaSessionManager` listener
     * hearing what a player is doing, and the two agree on nothing but the word: tapping
     * play inside Spotify produces this and no key event at all, while a headset button
     * produces a key event whether or not anything is playing.
     *
     * **Broadcast rather than addressed to a node id**, which is [RECORDING]'s arrangement
     * rather than [MEDIA_STORE]'s. That one routes because each node carries its own
     * high-water mark, so one photo is new to some armed triggers and not to others. There
     * is no mark here and no per-node state at all: a track change is equally new to every
     * armed node, and the two filters `trigger.media_playback` applies — which event, which
     * app — are string comparisons it can run itself.
     *
     * The registration behind it is still armed and disarmed per node, because a platform
     * listener that needs notification access must not be held while nothing wants it.
     */
    MEDIA_SESSION,

    /**
     * Somebody failed to unlock the phone.
     *
     * Its own source rather than [DISPLAY], which is documented as the screen turning
     * on and off and the device being unlocked — all three of which are things that
     * happen in ordinary use. This is the one that is not, and the filter a trigger
     * runs against it is `source` alone.
     *
     * **Broadcast rather than addressed to a node id**, on [RECORDING]'s reasoning: the
     * admin receiver hearing `onPasswordFailed` has no idea which macros want it, and a
     * wrong PIN is equally new to every armed node. Emitted through
     * [TriggerBus.emitOrHoldBroadcast] because a failed unlock nearly always arrives
     * with the engine cold — the phone has been locked, which is exactly when Android
     * has had every reason to reclaim the process.
     */
    SECURITY,
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
@Suppress("TooManyFunctions") // Two emit modes and two hold queues, each with its own drain.
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
     * Broadcast events parked while the engine was still coming up, each carrying
     * the set of nodes that have already taken it.
     */
    private class HeldBroadcast(val event: TriggerEvent, val deliveredTo: MutableSet<NodeId>)

    private val heldBroadcasts = mutableListOf<HeldBroadcast>()

    /**
     * Whether the engine is still arming its macros for the first time this
     * process. Starts true at class load — which is *before* anything can have
     * started arming — and is closed by [engineReady].
     */
    @Volatile
    private var starting = true

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
     * Delivers [event] live **and** parks a copy while the engine is still waking
     * up, for a fan-out source addressed to [NodeId.BROADCAST].
     *
     * The counterpart to [emitOrHold] for events that are not addressed to a node,
     * and it needs its own function because the two differ where it matters:
     * [emitOrHold]'s branches are *exclusive*, which is exactly right when
     * "somebody is collecting" answers "is the one node this is for collecting?".
     * For a broadcast it answers nothing of the kind — `rearmAll` arms macros one
     * at a time, so a tap during arming finds macro A subscribed, takes the live
     * branch, and is delivered to a macro that was never interested while the one
     * that was is still being read off disk.
     *
     * Delivering *and* parking would double-run without [HeldBroadcast.deliveredTo],
     * which is the piece that keeps "one transition, one run" true: a collector
     * that got the live copy never drains (a collection subscribes once), and a
     * collector that drains marks itself so a later re-arm cannot take it again.
     *
     * Parking stops at [engineReady]; what is already parked expires on
     * [BROADCAST_HOLD_MAX_AGE_MS].
     */
    fun emitOrHoldBroadcast(event: TriggerEvent) {
        _events.tryEmit(event)
        if (starting) holdBroadcast(event)
    }

    /**
     * Called once the engine has armed everything it is going to: nothing parks
     * after this, so a macro armed an hour later cannot replay an old event.
     *
     * It deliberately does **not** empty the queue, which is the tempting version
     * and would be wrong. Arming only *launches* a trigger's collector, so the last
     * macro `rearmAll` touched has almost certainly not subscribed by the time this
     * runs — clearing here would throw away the event a fraction of a second before
     * the node it was parked for arrived to take it. What is already parked is
     * therefore left to expire on its own, a window measured in seconds.
     *
     * The residue that leaves is one narrow case: a node that received an event
     * *live* is not recorded in [HeldBroadcast.deliveredTo], so re-arming it inside
     * that window — editing the macro, which is the only thing that re-arms one —
     * lets it drain the copy as well and run a second time. One extra run of a macro
     * you are actively editing is a far smaller fault than the one this exists to
     * fix, where a tag tapped on a cold start ran nothing at all.
     */
    fun engineReady() {
        starting = false
    }

    /**
     * Live events, preceded by whatever was held for [nodeId] and by any broadcast
     * this node has not yet been handed.
     *
     * The drain runs inside [onSubscription] — after this collector is registered
     * on the shared flow but before it is handed anything live. That is the exact
     * instant at which "nobody was listening" stops being true, which is why it
     * needs no lock of its own and never has to coordinate with the engine's
     * arming mutex. Draining after arming *returns* would not work: arming only
     * launches the collectors, so it finishes before any of them has subscribed.
     */
    fun eventsFor(nodeId: NodeId): Flow<TriggerEvent> = _events.onSubscription {
        takeHeld(nodeId).forEach { emit(it) }
        takeBroadcasts(nodeId).forEach { emit(it) }
    }

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

    private fun holdBroadcast(event: TriggerEvent) = synchronized(heldLock) {
        prune()
        heldBroadcasts += HeldBroadcast(
            event.copy(payload = event.payload + (KEY_HELD to "true")),
            mutableSetOf(),
        )
        while (heldBroadcasts.size > MAX_HELD_BROADCASTS) heldBroadcasts.removeAt(0)
    }

    /**
     * Everything parked that [nodeId] has not already been given, marking each as
     * taken. Marking rather than removing is what lets a second trigger node arm a
     * moment later and still receive the same tap.
     */
    private fun takeBroadcasts(nodeId: NodeId): List<TriggerEvent> = synchronized(heldLock) {
        prune()
        heldBroadcasts.filter { it.deliveredTo.add(nodeId) }.map { it.event }
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
        val broadcastCutoff = System.currentTimeMillis() - BROADCAST_HOLD_MAX_AGE_MS
        heldBroadcasts.removeAll { it.event.firedAtEpochMs < broadcastCutoff }
    }

    /** Drops every held event, and reopens the starting window. Test seam. */
    internal fun clearHeld() = synchronized(heldLock) {
        held.clear()
        heldBroadcasts.clear()
        starting = true
    }

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

    /**
     * Shorter than [HOLD_MAX_AGE_MS], because this is a backstop rather than the
     * mechanism: [engineReady] normally clears the queue, and what this covers is a
     * start that never finished. An NFC tap or a boot broadcast older than this has
     * stopped being something the user just did.
     */
    internal const val BROADCAST_HOLD_MAX_AGE_MS = 15_000L

    /** A fan-out event reaches every node, so far fewer are needed than per node. */
    private const val MAX_HELD_BROADCASTS = 8
}
