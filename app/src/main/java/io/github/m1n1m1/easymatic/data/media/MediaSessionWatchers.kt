package io.github.m1n1m1.easymatic.data.media

import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import io.github.m1n1m1.easymatic.core.model.NodeId
import io.github.m1n1m1.easymatic.core.service.LogLevel
import io.github.m1n1m1.easymatic.core.trigger.TriggerBus
import io.github.m1n1m1.easymatic.core.trigger.TriggerEvent
import io.github.m1n1m1.easymatic.core.trigger.TriggerSource
import io.github.m1n1m1.easymatic.engine.trigger.MediaEventCodec
import io.github.m1n1m1.easymatic.engine.trigger.PlaybackDiff
import io.github.m1n1m1.easymatic.engine.trigger.ScheduleHandle

/**
 * One `MediaSessionManager` listener for the whole process, shared by every armed
 * `trigger.media_playback`.
 *
 * `ImageWatchers`' shape, including why registration waits for the first arm rather than
 * happening in `ServiceLocator.init`: it needs notification access, so registering
 * unconditionally at start-up would be done before the user has granted anything and would
 * never recover without a process restart.
 *
 * **Two differences from the image watcher, both forced by there being no per-node state.**
 *
 * First, this holds a *set* of interested nodes where that one holds a map: there is no
 * spec to remember, because the two filters `trigger.media_playback` applies — which event,
 * which app — are string comparisons the node runs itself against a broadcast event. The
 * node ids are here to refcount the registration and for nothing else.
 *
 * Second, **the classification is not here**. [PlaybackDiff] in `engine/` decides what a
 * snapshot means; this file's whole job is to keep the platform listeners attached and to
 * feed it. That is `SensorBridge`'s division with the gesture detectors, and it is what
 * makes the started/paused/stopped/track-changed table testable on the JVM.
 *
 * **Nothing here is debounced by a timer**, unlike the image watcher's coalesced scan.
 * `MediaController.Callback` fires several times a second on a playing track as the position
 * advances, and every one of those produces an empty diff because position is not a fact
 * [PlaybackDiff] compares. Suppressing them by waiting would delay the real events too.
 */
class MediaSessionWatchers(context: Context) {

    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val lock = Any()
    private val interested = mutableSetOf<NodeId>()
    private val diff = PlaybackDiff()

    /** Attached controllers, by package, so a callback is added and removed exactly once. */
    private val attached = mutableMapOf<String, Attached>()

    /**
     * Labels resolved once per package rather than per callback.
     *
     * A `PackageManager` lookup runs in milliseconds and would otherwise run several times a
     * second for as long as anything is playing, which is the kind of cost that only shows
     * up as a warm phone.
     */
    private val labels = mutableMapOf<String, String>()

    private var listener: MediaSessionManager.OnActiveSessionsChangedListener? = null

    private class Attached(val controller: MediaController, val callback: MediaController.Callback)

    /**
     * Registers [nodeId]'s interest, returning the handle that withdraws it.
     *
     * There is deliberately **no baseline pass**, which is the opposite of `ImageWatchers`.
     * That one records where the gallery is so a newly armed macro does not run once per
     * photo already on the phone. Here the first snapshot *is* the baseline: [PlaybackDiff]
     * has seen nothing, so a player already running produces `STARTED` — and that is wrong
     * only if you think of it as history. It is not: arming a macro while music plays and
     * having it fire immediately would be a macro reacting to a state, where this reacts to
     * changes. So the first snapshot is taken **at registration**, before any node can be
     * collecting, exactly so the already-playing player is remembered rather than announced.
     */
    fun arm(nodeId: NodeId, onReport: (String, LogLevel) -> Unit): ScheduleHandle {
        synchronized(lock) {
            interested += nodeId
            if (listener == null) register(onReport)
        }
        return ScheduleHandle { withdraw(nodeId) }
    }

    private fun register(onReport: (String, LogLevel) -> Unit) {
        val created = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
            onSessions(controllers.orEmpty())
        }
        val manager = runCatching {
            appContext.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        }.getOrNull()
        val registered = manager != null && runCatching {
            manager.addOnActiveSessionsChangedListener(created, MediaSessions.component(appContext), handler)
        }.isSuccess
        if (!registered) {
            // The only way this fails is notification access being off, and it fails
            // *silently* to everything else — the trigger would sit armed for ever and never
            // fire, which is indistinguishable from a macro that is simply waiting.
            onReport(
                "Easymatic does not have notification access, so it cannot tell what any media player is doing",
                LogLevel.WARN,
            )
            return
        }
        listener = created
        // Seeds the diff with whatever is already playing, and deliberately announces none
        // of it: a macro armed while music runs must not fire as if it had just started.
        onSessions(MediaSessions.active(appContext), announce = false)
    }

    private fun withdraw(nodeId: NodeId) {
        synchronized(lock) {
            interested -= nodeId
            if (interested.isNotEmpty()) return
            listener?.let { active ->
                runCatching {
                    val manager =
                        appContext.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
                    manager.removeOnActiveSessionsChangedListener(active)
                }
            }
            listener = null
            attached.values.forEach { it.controller.unregisterCallback(it.callback) }
            attached.clear()
            labels.clear()
            // The remembered state goes too, so a re-arm takes a fresh baseline rather than
            // announcing everything that moved while nothing was listening.
            diff.reset()
        }
    }

    /**
     * Re-attaches callbacks to match [controllers], then reports whatever changed.
     *
     * The callbacks are what make this responsive at all: the sessions-changed listener
     * fires when a player appears or goes away, and everything in between — pausing,
     * skipping, a track ending — reaches us only through the per-controller callback.
     */
    private fun onSessions(controllers: List<MediaController>, announce: Boolean = true) {
        synchronized(lock) {
            if (listener == null) return
            val byPackage = controllers.associateBy { it.packageName.orEmpty() }
            (attached.keys - byPackage.keys).toList().forEach { gone ->
                attached.remove(gone)?.let { it.controller.unregisterCallback(it.callback) }
            }
            byPackage.forEach { (packageName, controller) ->
                if (attached[packageName]?.controller == controller) return@forEach
                attached.remove(packageName)?.let { it.controller.unregisterCallback(it.callback) }
                attached[packageName] = attach(controller)
            }
        }
        publish(announce)
    }

    private fun attach(controller: MediaController): Attached {
        val callback = object : MediaController.Callback() {
            override fun onPlaybackStateChanged(state: PlaybackState?) = publish()
            override fun onMetadataChanged(metadata: MediaMetadata?) = publish()

            /**
             * A session ending is reported here as well as through the sessions-changed
             * listener, and the two do not always both arrive — some players destroy the
             * session without the list being republished. Re-reading the list is what makes
             * either route produce the same stop event exactly once, since the diff is over
             * a snapshot rather than over deltas.
             */
            override fun onSessionDestroyed() = onSessions(MediaSessions.active(appContext))
        }
        controller.registerCallback(callback, handler)
        return Attached(controller, callback)
    }

    /**
     * Reads every attached controller and puts whatever changed on the bus.
     *
     * The snapshot and the diff happen under one lock, not two. Splitting them would let two
     * callbacks interleave between reading and comparing, which reorders the two snapshots
     * and reports a track change backwards.
     */
    private fun publish(announce: Boolean = true) {
        val changes = synchronized(lock) {
            if (listener == null) return
            diff.update(
                attached.values.map { entry ->
                    val packageName = entry.controller.packageName.orEmpty()
                    val label = labels.getOrPut(packageName) { MediaSessions.appLabel(appContext, packageName) }
                    MediaSessions.reading(entry.controller, label)
                },
            )
        }
        if (!announce) return
        changes.forEach { change ->
            TriggerBus.emit(
                TriggerEvent(
                    source = TriggerSource.MEDIA_SESSION,
                    // Broadcast: there is no per-node state here, so this fact is equally
                    // new to every armed node and each filters it itself.
                    triggerNodeId = NodeId.BROADCAST,
                    payload = MediaEventCodec.encode(change),
                ),
            )
        }
    }
}
