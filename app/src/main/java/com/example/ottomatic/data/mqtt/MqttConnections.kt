package com.example.ottomatic.data.mqtt

import com.example.ottomatic.core.model.NodeId
import com.example.ottomatic.core.service.HubLink
import com.example.ottomatic.core.service.LogLevel
import com.example.ottomatic.core.service.MqttReading
import com.example.ottomatic.core.trigger.TriggerBus
import com.example.ottomatic.core.trigger.TriggerEvent
import com.example.ottomatic.core.trigger.TriggerSource
import com.example.ottomatic.data.SmartHomeHubRepository
import com.example.ottomatic.domain.model.MqttAddress
import com.example.ottomatic.domain.model.SmartHomeKind
import com.example.ottomatic.engine.trigger.MqttPayload
import com.example.ottomatic.engine.trigger.MqttWatchSpec
import com.example.ottomatic.engine.trigger.ScheduleHandle
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentSkipListSet
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Every MQTT broker connection this phone holds, and everything that wants one.
 *
 * `HaConnections`' shape and its three callers: `AndroidMqtt` reads the cache and
 * publishes, `AndroidTriggerHost` registers armed trigger nodes, and `MacroEngineService`
 * starts and stops it through [HubLink]. All three are about the same connections, which
 * is why this is one object rather than three.
 *
 * Two things are genuinely different from the Home Assistant side, and both come from
 * MQTT having no request-response at all.
 *
 * **Nothing is subscribed speculatively.** Home Assistant's socket seeds itself with
 * `get_states`, so its cache holds every entity from the moment it connects. A broker has
 * no such command: the only way to learn a topic's value is to be *subscribed* when it is
 * published, so a cache that covered everything would mean subscribing to `#` and
 * receiving every message in the house — including a broker's own `$SYS` statistics
 * several times a second — to serve the handful of topics a macro actually names. So a
 * subscription is taken out for exactly what something asked for: one per armed trigger's
 * filter, and one per topic a value node has ever read. That is
 * `TriggerHost.sensorSamples`' rule about not waking everything, applied one layer lower.
 *
 * **A value read therefore subscribes, and the first one waits.** [lastMessage] takes out
 * the subscription if it is the first read of that topic and waits briefly, because
 * brokers deliver **retained** messages immediately on subscribe and a state topic is
 * almost always retained — Zigbee2MQTT, Tasmota and ESPHome all retain. So the first read
 * of a live topic answers, and every read after it is the map lookup the pull side
 * requires. A topic with no retained message answers null until something publishes to it,
 * which is the declared degradation and is also the literal truth: nothing anywhere knows
 * that value yet.
 *
 * The subscriptions a value read leaves behind are never dropped, and are capped for it —
 * see [MAX_PULL_TOPICS]. A macro reading a hundred topics is real; one reading ten
 * thousand is a loop over a generated name, and a broker should not be asked to serve it.
 */
// One object serving three callers; the connections, the cache and the routing table are
// one concern and splitting them would only move the coupling.
@Suppress("TooManyFunctions")
class MqttConnections(
    private val hubs: SmartHomeHubRepository,
    private val scope: CoroutineScope,
) : HubLink {

    /** One live connection and everything about it. */
    private class Connection(
        val hubId: String,
        /** What identifies "the same connection": a change here means reconnect. */
        val signature: String,
        val session: MqttSession,
    ) {
        val messages = ConcurrentHashMap<String, MqttReading>()

        /**
         * Every filter this connection is subscribed with, and how many things want it.
         *
         * Refcounted per *filter* rather than per node, on `HaConnections`' reasoning: ten
         * triggers watching `zigbee2mqtt/+/state` cost one subscription. It is also what
         * the reconnect path replays, which is the load-bearing use — see [onReady].
         */
        val subscriptions = mutableMapOf<String, Int>()

        /** Completed the first time the broker accepts the connection. */
        val connected = CompletableDeferred<Unit>()
    }

    /** One node's interest, and where to report back to. */
    private class Watcher(val spec: MqttWatchSpec, val report: (String, LogLevel) -> Unit)

    private val lock = Any()

    private val connections = mutableMapOf<String, Connection>()

    private val watchers = ConcurrentHashMap<NodeId, Watcher>()

    /** Where a running [refreshTopics] collects, keyed by hub. Empty the rest of the time. */
    private val scanners = ConcurrentHashMap<String, (MqttArrival) -> Unit>()

    @Volatile
    private var started = false

    private var watchJob: Job? = null

    // ---- HubLink ----

    override fun start() {
        synchronized(lock) {
            if (started) return
            started = true
            // Reconciled from the library rather than from a snapshot taken now, so a
            // broker added, re-addressed or re-credentialled while the engine runs reaches
            // the connection with nothing else being told to do it.
            watchJob = scope.launch {
                hubs.hubs.collect { library ->
                    reconcile(library.filter { it.kind == SmartHomeKind.MQTT && it.isComplete }.map { it.id })
                }
            }
        }
    }

    override fun stop() {
        val closing = synchronized(lock) {
            if (!started) return
            started = false
            watchJob?.cancel()
            watchJob = null
            connections.values.map { it.session }.also { connections.clear() }
        }
        closeAll(closing)
    }

    // ---- The read side ----

    /**
     * One topic from the cache, subscribing first if this is the first read of it.
     *
     * The wait is bounded and is paid **once per topic** rather than once per read, which
     * is what keeps `value.mqtt_topic` on the pull side: a retained message arrives within
     * milliseconds of the subscription being accepted, and everything after that is a map
     * lookup.
     */
    @Suppress("ReturnCount") // A wildcard topic, no connection and the pull cap are three different noes.
    internal suspend fun lastMessage(hubId: String, topic: String): MqttReading? {
        if (!MqttTopics.isPublishable(topic)) return null
        val connection = connection(hubId) ?: return null
        val fresh = synchronized(lock) {
            when {
                topic in connection.subscriptions -> false
                connection.subscriptions.size >= MAX_PULL_TOPICS -> return null
                else -> {
                    connection.subscriptions[topic] = connection.subscriptions.getOrDefault(topic, 0) + 1
                    true
                }
            }
        }
        if (fresh) {
            withContext(Dispatchers.IO) { connection.session.subscribe(topic, RETAINED_READ_QOS) }
            withTimeoutOrNull(RETAINED_WAIT_MS) {
                while (connection.messages[topic] == null) delay(POLL_MS)
            }
        }
        return connection.messages[topic]
    }

    fun isConnected(hubId: String): Boolean =
        synchronized(lock) { connections[hubId]?.session?.isConnected } == true

    // ---- The action side ----

    /** Publishes, or answers the sentence naming why it did not. */
    internal suspend fun publish(
        hubId: String,
        topic: String,
        payload: String,
        qos: Int,
        retain: Boolean,
    ): String? {
        val connection = connection(hubId) ?: return "Could not connect to the broker"
        return withContext(Dispatchers.IO) { connection.session.publish(topic, payload, qos, retain) }
    }

    /**
     * Listens on everything for a few seconds and records which topics spoke.
     *
     * The Refresh button's whole implementation, and the one place `#` is subscribed. It
     * is bounded in both directions — a few seconds, and a cap on how many names are kept
     * — because a broker has no directory to read and this is the only way to build one:
     * what it produces is *whatever was published while it listened*, which is exactly why
     * the field it feeds is a suggestion rather than a chooser.
     *
     * It is never run on an execution path. A macro that publishes to a topic no Refresh
     * has ever seen works perfectly; the list only exists so the topic field has something
     * to offer.
     */
    @Suppress("ReturnCount") // Each guard names a different reason there is nothing to listen on.
    suspend fun refreshTopics(hubId: String): String? {
        val hub = hubs.get(hubId) ?: return "That hub has been removed"
        if (hub.kind != SmartHomeKind.MQTT) return "That hub is not an MQTT broker"
        val connection = connection(hubId) ?: return "Could not connect to the broker"
        // Concurrent and sorted: it is written from the client library's own delivery
        // thread and read from this one, and the list it becomes is what a dropdown shows.
        val seen = ConcurrentSkipListSet<String>()
        val handle = { arrival: MqttArrival ->
            if (seen.size < MAX_SCANNED_TOPICS) {
                seen += arrival.topic
                Unit
            } else {
                Unit
            }
        }
        scanners[hubId] = handle
        try {
            withContext(Dispatchers.IO) { connection.session.subscribe(SCAN_FILTER, RETAINED_READ_QOS) }
                ?.let { return it }
            delay(SCAN_WINDOW_MS)
        } finally {
            scanners.remove(hubId)
            // Only dropped when nothing else wanted it: an armed trigger watching `#` is
            // unusual and entirely legal, and a Refresh must not silently disarm it.
            val wanted = synchronized(lock) { SCAN_FILTER in connection.subscriptions }
            if (!wanted) withContext(Dispatchers.IO) { connection.session.unsubscribe(SCAN_FILTER) }
        }
        hubs.setTopics(hubId, seen.toList(), System.currentTimeMillis())
        return null
    }

    // ---- The trigger side ----

    /**
     * Registers [nodeId]'s interest, and answers the handle that drops it.
     *
     * The registration lives here rather than the trigger filtering a broadcast, on
     * `HaConnections.arm`'s reasoning: `TriggerEvent` carries one node id, and a busy
     * broker publishes constantly.
     */
    fun arm(nodeId: NodeId, spec: MqttWatchSpec, onReport: (String, LogLevel) -> Unit): ScheduleHandle {
        watchers[nodeId] = Watcher(spec, onReport)
        scope.launch { subscribe(spec.hubId, spec.topicFilter, onReport) }

        return ScheduleHandle {
            watchers.remove(nodeId)
            scope.launch { release(spec.hubId, spec.topicFilter) }
        }
    }

    private suspend fun subscribe(hubId: String, filter: String, onReport: (String, LogLevel) -> Unit) {
        val connection = connection(hubId) ?: run {
            onReport("Could not connect to the broker, so this trigger cannot fire yet", LogLevel.WARN)
            return
        }
        val first = synchronized(lock) {
            val count = connection.subscriptions.getOrDefault(filter, 0)
            connection.subscriptions[filter] = count + 1
            count == 0
        }
        if (!first) return
        withContext(Dispatchers.IO) { connection.session.subscribe(filter, WATCH_QOS) }
            ?.let { onReport(it, LogLevel.WARN) }
    }

    private suspend fun release(hubId: String, filter: String) {
        val connection = synchronized(lock) { connections[hubId] } ?: return
        val last = synchronized(lock) {
            val count = connection.subscriptions.getOrDefault(filter, 0) - 1
            if (count <= 0) connection.subscriptions.remove(filter) else connection.subscriptions[filter] = count
            count <= 0
        }
        if (last) withContext(Dispatchers.IO) { connection.session.unsubscribe(filter) }
    }

    // ---- Connection lifecycle ----

    /** The connection for [hubId], opening one if the engine is not holding it already. */
    private suspend fun connection(hubId: String): Connection? {
        synchronized(lock) { connections[hubId] }?.let { existing ->
            withTimeoutOrNull(CONNECT_WAIT_MS) { existing.connected.await() }
            return existing
        }
        return openOnDemand(hubId)
    }

    private fun reconcile(wantedHubIds: List<String>) {
        val closing = mutableListOf<MqttSession>()
        synchronized(lock) {
            val wanted = wantedHubIds.toSet()
            connections.keys.filterNot { it in wanted }.toList().forEach { id ->
                connections.remove(id)?.let { closing += it.session }
            }
            wanted.forEach { id ->
                val signature = signatureOf(id) ?: return@forEach
                val existing = connections[id]
                // Keyed on what actually decides the connection, so renaming a broker does
                // not drop it — `HaConnections.signatureOf`'s trick and its reason.
                if (existing != null && existing.signature == signature) return@forEach
                existing?.let { closing += it.session }
                connections[id] = open(id, signature)
            }
        }
        closeAll(closing)
    }

    /**
     * Closes sessions **outside [lock]**, and never inside it.
     *
     * This is not tidiness. Closing waits for the broker to acknowledge the disconnect,
     * and the client library completes that on its own callback thread — the same thread
     * that calls [onReady] and [onArrival], both of which take [lock]. Closing while
     * holding it therefore deadlocks the two against each other, and what it looks like
     * from outside is the app freezing the moment a broker is edited or removed.
     *
     * On the process scope rather than the caller's, because [stop] cannot suspend and
     * because a disconnect that hangs must not hold up the service stopping.
     */
    private fun closeAll(sessions: List<MqttSession>) {
        if (sessions.isEmpty()) return
        scope.launch { withContext(Dispatchers.IO) { sessions.forEach { it.close() } } }
    }

    /**
     * What identifies "the same connection".
     *
     * The address and the login, hashed. A **null password is not a failure here**, unlike
     * on the Home Assistant side: an anonymous broker is ordinary, so what would be a
     * missing credential there is simply a blank one.
     */
    @Suppress("ReturnCount") // A deleted hub and an unreadable address are both "no connection".
    private fun signatureOf(hubId: String): String? {
        val hub = hubs.get(hubId) ?: return null
        val address = MqttAddress.parse(hub.host) ?: return null
        return "${address.serverUri}|${hub.username}|${hubs.brokerPassword(hubId).orEmpty().hashCode()}"
    }

    private fun open(hubId: String, signature: String): Connection {
        val hub = hubs.get(hubId)
        val address = MqttAddress.parse(hub?.host.orEmpty())
        lateinit var connection: Connection
        val session = MqttSession(
            serverUri = address?.serverUri.orEmpty(),
            clientId = MqttSession.clientIdFor(hubId),
            username = hub?.username.orEmpty(),
            password = hubs.brokerPassword(hubId).orEmpty(),
            listener = object : MqttSession.Events {
                override fun onReady() = onReady(connection)

                override fun onLost(reason: String) = report(hubId, reason, LogLevel.WARN)

                override fun onArrival(arrival: MqttArrival) = onArrival(hubId, connection, arrival)
            },
        )
        connection = Connection(hubId, signature, session)
        scope.launch {
            withContext(Dispatchers.IO) { session.connect() }
                ?.let { report(hubId, it, LogLevel.WARN) }
        }
        return connection
    }

    /**
     * Re-sends every subscription, on the first connect and on every reconnect alike.
     *
     * **This is the one thing in this file that fails silently if it is wrong.** The
     * client library reconnects on its own and does not restore subscriptions on a clean
     * session, so without this a connection that drops for a moment comes back looking
     * entirely healthy — connected, no error anywhere — and never delivers another
     * message. Nothing in the app would say so; the macro would simply stop running.
     *
     * It does not distinguish first connect from reconnect, deliberately: a path that runs
     * only after a failure is a path that is broken.
     */
    private fun onReady(connection: Connection) {
        connection.connected.complete(Unit)
        val filters = synchronized(lock) { connection.subscriptions.keys.toList() }
        scope.launch {
            withContext(Dispatchers.IO) {
                filters.forEach { filter -> connection.session.subscribe(filter, WATCH_QOS) }
            }
        }
    }

    private fun onArrival(hubId: String, connection: Connection, arrival: MqttArrival) {
        val reading = MqttReading(
            topic = arrival.topic,
            payload = arrival.payload,
            retained = arrival.retained,
            qos = arrival.qos,
            receivedAtEpochMs = System.currentTimeMillis(),
        )
        connection.messages[arrival.topic] = reading
        scanners[hubId]?.invoke(arrival)
        deliver(hubId, reading)
    }

    /**
     * Opens a connection outside the engine's lifetime.
     *
     * The editor's way in, on `HaConnections.openOnDemand`'s reasoning: a picker, a preview
     * run and a value node pulled with the service stopped all need a connection, and none
     * of them can start the engine.
     */
    @Suppress("ReturnCount") // Each guard is a different reason there is nothing to open.
    private suspend fun openOnDemand(hubId: String): Connection? {
        val hub = hubs.get(hubId) ?: return null
        if (hub.kind != SmartHomeKind.MQTT || !hub.isComplete) return null
        val connection = synchronized(lock) {
            val signature = signatureOf(hubId) ?: return null
            connections[hubId]?.takeIf { it.signature == signature }
                ?: open(hubId, signature).also { connections[hubId] = it }
        }
        withTimeoutOrNull(CONNECT_WAIT_MS) { connection.connected.await() }
        return connection
    }

    // ---- Publishing to the bus ----

    /**
     * Wakes exactly the nodes that asked for this topic.
     *
     * The matching is [MqttTopics]' rather than the broker's, and it has to be: the
     * connection is shared, so a message arrives because *somebody* subscribed to a filter
     * it matches, and which nodes those are is this app's question to answer.
     */
    private fun deliver(hubId: String, reading: MqttReading) {
        val interested = watchers.filterValues { watcher ->
            watcher.spec.hubId == hubId && MqttTopics.matches(watcher.spec.topicFilter, reading.topic)
        }
        if (interested.isEmpty()) return
        val payload = mapOf(
            MqttPayload.HUB_ID to hubId,
            MqttPayload.TOPIC to reading.topic,
            MqttPayload.PAYLOAD to reading.payload,
            MqttPayload.RETAINED to reading.retained.toString(),
            MqttPayload.QOS to reading.qos.toString(),
            MqttPayload.RECEIVED_AT to reading.receivedAtEpochMs.toString(),
        )
        interested.keys.forEach { nodeId ->
            TriggerBus.emit(TriggerEvent(TriggerSource.MQTT, nodeId, payload))
        }
    }

    /** Tells every node watching [hubId] what happened to the connection they depend on. */
    private fun report(hubId: String, message: String, level: LogLevel) {
        watchers.values.filter { it.spec.hubId == hubId }.forEach { it.report(message, level) }
    }

    private companion object {
        /**
         * How many topics one broker's connection will hold subscriptions for on the pull
         * side.
         *
         * A bound rather than a target: a house has tens of interesting topics and a macro
         * reading ten thousand of them is a loop over a generated name, which a broker
         * should not be asked to serve and which no user meant.
         */
        const val MAX_PULL_TOPICS = 512

        /** How many names one Refresh keeps. A busy broker can publish faster than this. */
        const val MAX_SCANNED_TOPICS = 500

        /** Everything, for the few seconds a Refresh listens. The only use of `#` in the app. */
        const val SCAN_FILTER = "#"
        const val SCAN_WINDOW_MS = 4_000L

        /** Long enough for a retained message, short enough not to stall a pulled value. */
        const val RETAINED_WAIT_MS = 1_500L
        const val POLL_MS = 25L
        const val CONNECT_WAIT_MS = 6_000L

        /**
         * Both subscriptions are at-least-once.
         *
         * Not at-most-once, which would let a broker drop a state change under load and
         * leave a macro looking like it missed one; and not exactly-once, whose four-packet
         * handshake buys nothing here — a duplicate state message is the same value twice,
         * and a duplicate *event* is what `trigger.mqtt_message` is watching for anyway.
         */
        const val WATCH_QOS = 1
        const val RETAINED_READ_QOS = 1
    }
}
