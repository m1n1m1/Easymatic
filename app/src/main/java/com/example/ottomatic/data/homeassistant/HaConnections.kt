package com.example.ottomatic.data.homeassistant

import com.example.ottomatic.core.model.NodeId
import com.example.ottomatic.core.service.HubLink
import com.example.ottomatic.core.service.LogLevel
import com.example.ottomatic.core.trigger.TriggerBus
import com.example.ottomatic.core.trigger.TriggerEvent
import com.example.ottomatic.core.trigger.TriggerSource
import com.example.ottomatic.data.SmartHomeHubRepository
import com.example.ottomatic.domain.model.SmartHomeKind
import com.example.ottomatic.engine.trigger.HaPayload
import com.example.ottomatic.engine.trigger.HaWatchSpec
import com.example.ottomatic.engine.trigger.ScheduleHandle
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Every Home Assistant connection this phone holds, and everything that wants one.
 *
 * Held by `ServiceLocator` and shared by three callers with three different needs, which
 * is why it is one object rather than three: `AndroidHomeAssistant` reads its cache and
 * sends its service calls, `AndroidTriggerHost` registers armed trigger nodes with it,
 * and `MacroEngineService` starts and stops it through [HubLink]. All three are about
 * the same sockets.
 *
 * Modelled on `MailWatchers`, and it takes three things from it directly: the
 * `SupervisorJob + CoroutineExceptionHandler` scope, where the **handler** is the part
 * that matters (a supervisor stops one child cancelling its siblings and does nothing at
 * all about the exception, which would otherwise take the process down over a dropped
 * socket); reference counting per *thing watched* rather than per node, so ten triggers
 * on one event type cost one subscription; and the teardown rule that a cancel must be
 * effective by the time it returns.
 *
 * Where it differs is the reason [HubLink] exists at all. `MailWatchers` arms nothing
 * until a trigger asks, which is right when the only consumer is a trigger. Here the
 * socket additionally keeps the cache `value.ha_state` reads, so it is opened for
 * **every configured hub** as soon as the engine starts — tying it to armed triggers
 * would make a value node answer null in every macro that has no Home Assistant trigger
 * in it, which is most of them.
 */
// One object serving three callers; the sockets, the cache and the routing table are
// one concern and splitting them would only move the coupling.
@Suppress("TooManyFunctions")
class HaConnections(
    private val hubs: SmartHomeHubRepository,
    private val scope: CoroutineScope,
    private val tokens: HaTokens = HaTokens(hubs),
) : HubLink {

    /** One live connection and everything about it. */
    private class Connection(
        val hubId: String,
        /** What identifies "the same connection": a change here means reconnect. */
        val signature: String,
        val socket: HaSocket,
    ) {
        val states = ConcurrentHashMap<String, HaResources.HaState>()

        /** Completed when the seeding `get_states` has landed. */
        val seeded = CompletableDeferred<Unit>()

        @Volatile
        var job: Job? = null

        @Volatile
        var permanentlyFailed = false
    }

    /** One node's interest, and where to report back to. */
    private class Watcher(val spec: HaWatchSpec, val report: (String, LogLevel) -> Unit)

    private val lock = Any()

    private val connections = mutableMapOf<String, Connection>()

    private val watchers = ConcurrentHashMap<NodeId, Watcher>()

    private val client by lazy { HaSocket.client() }

    @Volatile
    private var started = false

    private var watchJob: Job? = null

    // ---- HubLink ----

    override fun start() {
        synchronized(lock) {
            if (started) return
            started = true
            // Reconciled from the library rather than from a snapshot taken now, so a
            // hub added, re-addressed or re-tokened while the engine runs reaches the
            // socket without anything else being told to do it — which is what lets
            // SmartHomeViewModel go on not re-arming anything.
            watchJob = scope.launch {
                hubs.hubs.collect { library ->
                    reconcile(library.filter { it.kind == SmartHomeKind.HOME_ASSISTANT && it.isComplete }.map { it.id })
                }
            }
        }
    }

    override fun stop() {
        synchronized(lock) {
            if (!started) return
            started = false
            watchJob?.cancel()
            watchJob = null
            connections.values.forEach { close(it) }
            connections.clear()
        }
    }

    // ---- The read side ----

    /**
     * One entity from the cache, waiting only for a connection that is still filling.
     *
     * The wait is what makes the cold-start case behave: a value node pulled in the
     * first second after the process came up would otherwise read null once, for no
     * reason the user could see. It is bounded, and it happens **once per connection**
     * rather than once per read — every read after the seed lands is a map lookup.
     */
    internal suspend fun state(hubId: String, entityId: String): HaResources.HaState? {
        val connection = synchronized(lock) { connections[hubId] } ?: run {
            // Nothing is connected: either the engine is not running or this hub is not
            // one. Opening on demand is what makes a value node work from the editor's
            // preview run, where there is no service at all.
            openOnDemand(hubId) ?: return null
        }
        if (!connection.seeded.isCompleted) {
            withTimeoutOrNull(SEED_WAIT_MS) { connection.seeded.await() }
        }
        return connection.states[entityId]
    }

    fun isConnected(hubId: String): Boolean = synchronized(lock) { connections[hubId]?.socket?.isConnected } == true

    // ---- The trigger side ----

    /**
     * Registers [nodeId]'s interest, and answers the handle that drops it.
     *
     * The registration lives here rather than the trigger filtering a broadcast,
     * because `TriggerEvent` carries **one** node id and a busy install emits hundreds
     * of `state_changed` a minute — broadcasting them would wake every armed trigger in
     * the process to run its own filter chain, which is exactly what
     * `TriggerHost.sensorSamples`' KDoc refuses to do.
     */
    fun arm(nodeId: NodeId, hubId: String, spec: HaWatchSpec, onReport: (String, LogLevel) -> Unit): ScheduleHandle {
        watchers[nodeId] = Watcher(spec, onReport)
        val eventType = (spec as? HaWatchSpec.EventWatch)?.eventType
        if (eventType != null) {
            synchronized(lock) { connections[hubId] }?.socket?.watch(eventType)
                ?: scope.launch { openOnDemand(hubId)?.socket?.watch(eventType) }
        }
        return ScheduleHandle {
            watchers.remove(nodeId)
            if (eventType != null && watchers.values.none { matchesType(it.spec, eventType) }) {
                synchronized(lock) { connections[hubId] }?.socket?.unwatch(eventType)
            }
        }
    }

    private fun matchesType(spec: HaWatchSpec, eventType: String): Boolean =
        spec is HaWatchSpec.EventWatch && spec.eventType == eventType

    // ---- Connection lifecycle ----

    private fun reconcile(wantedHubIds: List<String>) {
        synchronized(lock) {
            val wanted = wantedHubIds.toSet()
            connections.keys.filterNot { it in wanted }.forEach { id ->
                connections.remove(id)?.let(::close)
            }
            wanted.forEach { id ->
                val signature = signatureOf(id) ?: return@forEach
                val existing = connections[id]
                // Keyed on what actually decides the connection, so renaming a hub does
                // not drop its socket — Workflow.runtimeSignature()'s trick, for its
                // reason: an edit that changes nothing the connection depends on should
                // cost nothing.
                if (existing != null && existing.signature == signature) return@forEach
                existing?.let(::close)
                connections[id] = open(id, signature)
            }
        }
    }

    @Suppress("ReturnCount") // A deleted hub and an unreadable token are both "no connection".
    /**
     * What identifies "the same connection".
     *
     * The **stored** credential rather than a renewed one, and non-suspending because of
     * it: renewal is the socket's own business as it connects, and reconciling against a
     * freshly renewed token would tear down and rebuild every OAuth connection each time
     * one expired — which is exactly when it is least useful to lose it.
     */
    private fun signatureOf(hubId: String): String? {
        val hub = hubs.get(hubId) ?: return null
        val token = hubs.accessToken(hubId) ?: hubs.refreshToken(hubId) ?: return null
        return "${hub.host}|${token.hashCode()}"
    }

    private fun open(hubId: String, signature: String): Connection {
        val hub = hubs.get(hubId)
        lateinit var connection: Connection
        val socket = HaSocket(
            client = client,
            baseUrl = hub?.host.orEmpty(),
            // Read at handshake time, after the renewal below has run.
            token = { hubs.accessToken(hubId).orEmpty() },
            listener = object : HaSocket.Events {
                override fun onReady(states: List<HaResources.HaState>) {
                    connection.states.putAll(states.associateBy { it.entityId })
                    connection.seeded.complete(Unit)
                }

                override fun onStateChanged(previous: HaResources.HaState?, current: HaResources.HaState) {
                    connection.states[current.entityId] = current
                    publishStateChange(hubId, previous, current)
                }

                override fun onEvent(eventType: String, data: JsonObject, origin: String) {
                    publishEvent(hubId, eventType, data, origin)
                }

                override fun onClosed(reason: String, permanent: Boolean, level: LogLevel) {
                    connection.permanentlyFailed = permanent
                    report(hubId, reason, level)
                    if (!permanent) reconnect(connection)
                }
            },
        )
        connection = Connection(hubId, signature, socket)
        // Renew before connecting rather than after being refused. A reconnect after an
        // hour down is precisely when an OAuth token has expired, and `auth_invalid` is
        // deliberately not retried — so being refused there would take the connection
        // down permanently over something the app can fix by itself.
        scope.launch {
            tokens.accessToken(hubId)
            socket.connect()
        }
        return connection
    }

    private fun close(connection: Connection) {
        connection.job?.cancel()
        connection.socket.close()
    }

    /**
     * Opens a connection outside the engine's lifetime and waits for its seed.
     *
     * The editor's way in: a picker, a preview run and a value node pulled with the
     * service stopped all need a cache, and none of them can start the engine. The
     * connection then joins the ordinary set and is reconciled with the rest.
     */
    @Suppress("ReturnCount") // Each guard is a different reason there is nothing to open.
    private suspend fun openOnDemand(hubId: String): Connection? {
        val hub = hubs.get(hubId) ?: return null
        if (hub.kind != SmartHomeKind.HOME_ASSISTANT || !hub.isComplete) return null
        val connection = synchronized(lock) {
            val signature = signatureOf(hubId) ?: return null
            connections[hubId]?.takeIf { it.signature == signature }
                ?: open(hubId, signature).also { connections[hubId] = it }
        }
        withTimeoutOrNull(SEED_WAIT_MS) { connection.seeded.await() }
        return connection
    }

    /**
     * Backs off and tries again.
     *
     * `MailWatchers`' schedule, and its one subtlety: the backoff resets on a connection
     * that **lasted**, not on one that merely opened. A server refusing at the
     * application layer accepts the socket every time, so resetting on connect would
     * turn the backoff into a tight loop.
     */
    private fun reconnect(connection: Connection) {
        if (connection.job?.isActive == true) return
        connection.job = scope.launch {
            var backoff = INITIAL_BACKOFF_MS
            while (isActive && !connection.permanentlyFailed) {
                delay(backoff)
                if (!isStillWanted(connection)) return@launch
                tokens.accessToken(connection.hubId)
                connection.socket.connect()
                val openedAt = System.currentTimeMillis()
                // Give it a moment to fail; if it is still up after HEALTHY_MS the
                // backoff has earned its reset.
                delay(HEALTHY_MS)
                if (connection.socket.isConnected && System.currentTimeMillis() - openedAt >= HEALTHY_MS) return@launch
                backoff = (backoff * 2).coerceAtMost(MAX_BACKOFF_MS)
            }
        }
    }

    private fun isStillWanted(connection: Connection): Boolean =
        synchronized(lock) { connections[connection.hubId] === connection }

    // ---- Publishing ----

    private fun publishStateChange(
        hubId: String,
        previous: HaResources.HaState?,
        current: HaResources.HaState,
    ) {
        val interested = watchers.filterValues { watcher ->
            (watcher.spec as? HaWatchSpec.StateWatch)?.let {
                it.hubId == hubId && it.entityId == current.entityId
            } == true
        }
        if (interested.isEmpty()) return
        val payload = mapOf(
            HaPayload.HUB_ID to hubId,
            HaPayload.ENTITY_ID to current.entityId,
            HaPayload.FRIENDLY_NAME to current.friendlyName,
            HaPayload.STATE to current.state,
            HaPayload.PREVIOUS_STATE to previous?.state.orEmpty(),
            HaPayload.UNIT to current.attributes["unit_of_measurement"]?.toString()?.trim('"').orEmpty(),
            // Flattened to JSON text because TriggerEvent.payload is Map<String, String>
            // — which is also what makes it readable with transform.json_read, the road
            // action.http and trigger.api already take.
            HaPayload.ATTRIBUTES to json.encodeToString(JsonObject.serializer(), current.attributes),
            HaPayload.CHANGED_AT to System.currentTimeMillis().toString(),
        )
        interested.keys.forEach { nodeId ->
            TriggerBus.emit(TriggerEvent(TriggerSource.HOME_ASSISTANT, nodeId, payload))
        }
    }

    private fun publishEvent(hubId: String, eventType: String, data: JsonObject, origin: String) {
        val interested = watchers.filterValues { watcher ->
            (watcher.spec as? HaWatchSpec.EventWatch)?.let {
                it.hubId == hubId && it.eventType == eventType
            } == true
        }
        if (interested.isEmpty()) return
        val payload = mapOf(
            HaPayload.HUB_ID to hubId,
            HaPayload.EVENT_TYPE to eventType,
            HaPayload.EVENT_DATA to json.encodeToString(JsonObject.serializer(), data),
            HaPayload.ORIGIN to origin,
            HaPayload.CHANGED_AT to System.currentTimeMillis().toString(),
        )
        interested.keys.forEach { nodeId ->
            TriggerBus.emit(TriggerEvent(TriggerSource.HOME_ASSISTANT, nodeId, payload))
        }
    }

    /** Tells every node watching [hubId] what happened to the connection they depend on. */
    private fun report(hubId: String, message: String, level: LogLevel) {
        if (message.isBlank()) return
        watchers.values
            .filter { hubIdOf(it.spec) == hubId }
            .forEach { it.report(message, level) }
    }

    private fun hubIdOf(spec: HaWatchSpec): String = when (spec) {
        is HaWatchSpec.StateWatch -> spec.hubId
        is HaWatchSpec.EventWatch -> spec.hubId
    }

    private val json = Json

    private companion object {
        /**
         * How long a read waits for a connection that is still filling.
         *
         * Long enough for a LAN round trip and a large `get_states`, short enough that a
         * server which is simply not there does not stall a macro. Paid once per
         * connection, never per read.
         */
        const val SEED_WAIT_MS = 3_000L

        const val INITIAL_BACKOFF_MS = 5_000L
        const val MAX_BACKOFF_MS = 5 * 60 * 1_000L

        /** A connection that lasted this long is what resets the backoff. */
        const val HEALTHY_MS = 30_000L
    }
}
