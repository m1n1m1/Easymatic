package com.example.ottomatic.data.homeassistant

import com.example.ottomatic.core.service.LogLevel
import com.example.ottomatic.domain.model.HaBaseUrl
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * One connection to one Home Assistant instance.
 *
 * Everything about *when* to connect and *who wants what* lives in [HaConnections];
 * this is the socket and the protocol on it, and nothing else. It reports through
 * callbacks rather than reaching for the bus, so it can be reasoned about — and, when
 * that becomes worth doing, driven — without a server.
 *
 * **The handshake order is load-bearing**, and one part of it is easy to get wrong in a
 * way that is invisible for hours: `get_states` is sent **before** the event
 * subscription is acknowledged and before anything else. Without that seed the cache
 * holds only entities that have changed *since the app started*, which on a quiet house
 * is almost nothing — so every `value.ha_state` would read null all morning and start
 * working, mysteriously, some time in the afternoon. Seeding is the difference between
 * "warm" and "eventually warm".
 *
 * **Message ids restart at 1 on every connection.** They must strictly increase within
 * one, and a reconnect is a new one; carrying the counter across works by accident until
 * the day the server checks.
 *
 * **`auth_invalid` is not retried.** It is a credential failure, not a network one, and
 * backing off from it would mean a phone quietly re-presenting a revoked token every
 * half hour for ever. It is reported and the socket stays down until something changes.
 */
// The protocol sets the count: a handshake, a subscription lifecycle and four
// callbacks.
@Suppress("TooManyFunctions")
internal class HaSocket(
    private val client: OkHttpClient,
    private val baseUrl: String,
    /**
     * Read afresh on every connect rather than captured once.
     *
     * A reconnect after an hour down is exactly when an OAuth access token has expired,
     * so a token captured at construction is the one guaranteed to be stale by the time
     * it is needed. The owner renews before calling [connect]; this reads whatever is
     * stored at that moment.
     */
    private val token: () -> String,
    private val listener: Events,
) {

    /** What the socket tells its owner. */
    interface Events {
        /** Authenticated, and the initial state snapshot has landed. */
        fun onReady(states: List<HaResources.HaState>)

        /** One entity changed. [previous] is null for an entity that has just appeared. */
        fun onStateChanged(previous: HaResources.HaState?, current: HaResources.HaState)

        /** An event on a subscribed type, other than `state_changed`. */
        fun onEvent(eventType: String, data: JsonObject, origin: String)

        /** The socket went down. [permanent] means do not retry — the credential was refused. */
        fun onClosed(reason: String, permanent: Boolean, level: LogLevel)
    }

    private val ids = AtomicInteger(0)

    @Volatile
    private var socket: WebSocket? = null

    @Volatile
    private var authenticated = false

    /** Which subscription id belongs to which event type, so it can be dropped again. */
    private val subscriptions = ConcurrentHashMap<String, Int>()

    /** Event types wanted, applied on connect and as they are added. */
    private val wanted = ConcurrentHashMap.newKeySet<String>()

    /** The id of the seeding `get_states`, so its result can be told from any other. */
    @Volatile
    private var seedId = 0

    val isConnected: Boolean get() = authenticated

    /** Opens the socket. A no-op when one is already open. */
    fun connect() {
        if (socket != null) return
        val url = HaBaseUrl.wsUrl(baseUrl) ?: run {
            listener.onClosed(HaBaseUrl.REQUIREMENT, permanent = true, level = LogLevel.ERROR)
            return
        }
        ids.set(0)
        authenticated = false
        subscriptions.clear()
        socket = client.newWebSocket(Request.Builder().url(url).build(), Handler())
    }

    /** Closes the socket. Safe to call when there is none. */
    fun close() {
        authenticated = false
        socket?.close(NORMAL_CLOSURE, null)
        socket = null
    }

    /**
     * Adds [eventType] to what this connection listens for.
     *
     * Idempotent, because several trigger nodes may want the same type and must cost
     * one subscription between them — `MailWatchers`' per-mailbox refcount, applied to
     * an event type. The subscription is sent now when the socket is up and on connect
     * when it is not, so arming a trigger before the socket lands is not a lost one.
     */
    fun watch(eventType: String) {
        if (eventType.isBlank() || !wanted.add(eventType)) return
        if (authenticated) subscribe(eventType)
    }

    /** Drops [eventType]. The last node interested in it having gone is the caller's business. */
    fun unwatch(eventType: String) {
        if (!wanted.remove(eventType)) return
        val subscriptionId = subscriptions.remove(eventType) ?: return
        send(HaMessages.unsubscribeEvents(nextId(), subscriptionId))
    }

    private fun nextId(): Int = ids.incrementAndGet()

    private fun send(frame: String) {
        socket?.send(frame)
    }

    private fun subscribe(eventType: String) {
        val id = nextId()
        subscriptions[eventType] = id
        send(HaMessages.subscribeEvents(id, eventType))
    }

    private inner class Handler : WebSocketListener() {

        override fun onMessage(webSocket: WebSocket, text: String) {
            when (val frame = HaMessages.parse(text)) {
                is HaMessages.Frame.AuthRequired -> webSocket.send(HaMessages.auth(token()))
                is HaMessages.Frame.AuthOk -> onAuthenticated()
                is HaMessages.Frame.AuthInvalid ->
                    // Deliberately permanent: re-presenting a revoked token every half
                    // hour for ever is not a retry, it is a loop.
                    listener.onClosed(authRefused(frame.message), permanent = true, level = LogLevel.WARN)
                is HaMessages.Frame.Result -> onResult(frame)
                is HaMessages.Frame.Event -> onEvent(frame)
                HaMessages.Frame.Pong, HaMessages.Frame.Unknown -> Unit
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            authenticated = false
            socket = null
            listener.onClosed(t.message.orEmpty(), permanent = false, level = LogLevel.WARN)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            authenticated = false
            socket = null
            // A close the server initiated is ordinary — a restart, an update — and is
            // reported at INFO so a nightly Home Assistant upgrade does not file a
            // warning in every macro watching it.
            listener.onClosed(reason, permanent = false, level = LogLevel.INFO)
        }
    }

    private fun onAuthenticated() {
        authenticated = true
        // Seed first, subscribe second. See the class KDoc: without the seed the cache
        // holds only what has changed since the app started.
        seedId = nextId()
        send(HaMessages.getStates(seedId))
        subscribe(HaMessages.STATE_CHANGED)
        wanted.forEach { subscribe(it) }
    }

    private fun onResult(frame: HaMessages.Frame.Result) {
        if (frame.id != seedId) return
        if (frame.success) {
            listener.onReady(HaMessages.statesOf(frame.body))
        } else {
            // The socket is up and authenticated but the cache will never fill, which
            // is worse than being disconnected because nothing else would report it.
            listener.onClosed(frame.error.ifBlank { STATES_REFUSED }, permanent = false, level = LogLevel.WARN)
        }
    }

    private fun onEvent(frame: HaMessages.Frame.Event) {
        if (frame.eventType == HaMessages.STATE_CHANGED) {
            val (previous, current) = HaMessages.stateChange(frame.data)
            // A null `new_state` is an entity that has just been removed. There is
            // nothing to cache and nothing a trigger could usefully say about it.
            current?.let { listener.onStateChanged(previous, it) }
        }
        // A state change is also an ordinary event on the bus, so `trigger.ha_event`
        // watching `state_changed` still sees it. That is why the two triggers do not
        // share a filter: one arrival can legitimately produce both.
        listener.onEvent(frame.eventType, frame.data, frame.origin)
    }

    private fun authRefused(message: String): String =
        "Home Assistant refused the token${message.takeIf { it.isNotBlank() }?.let { " — $it" }.orEmpty()}"

    internal companion object {
        private const val NORMAL_CLOSURE = 1000
        private const val STATES_REFUSED = "Home Assistant would not list its entities"

        /**
         * The shared client, built once for the process.
         *
         * `readTimeout(0)` because a websocket that has said nothing for a minute is a
         * quiet house, not a dead connection — and `pingInterval` is what actually
         * decides that. Thirty seconds is what notices a socket dropped by carrier NAT
         * or by a router's idle timeout, which `MailWatchers`' KDoc describes as the
         * failure that sits there *believing it is healthy*.
         */
        fun client(): OkHttpClient = OkHttpClient.Builder()
            .pingInterval(PING_INTERVAL_SECONDS, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()

        private const val PING_INTERVAL_SECONDS = 30L
        private const val CONNECT_TIMEOUT_SECONDS = 10L
    }
}
