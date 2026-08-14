package com.example.ottomatic.data.homeassistant

import com.example.ottomatic.core.service.LogLevel
import com.example.ottomatic.domain.model.HaBaseUrl
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
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

        /**
         * One of Home Assistant's own triggers fired.
         *
         * [subscriptionId] is the **only** thing saying which node wanted it, and that is a
         * fact about the protocol rather than a shortcut here: a trigger frame carries no
         * event type, no entity and no trigger name — only the id of the message that asked
         * for it. [payload] is `variables.trigger`, which is whatever that trigger platform
         * publishes and is frequently very little.
         */
        fun onTriggerFired(subscriptionId: Int, payload: JsonObject)

        /** The socket went down. [permanent] means do not retry — the credential was refused. */
        fun onClosed(reason: String, permanent: Boolean, level: LogLevel)
    }

    private val ids = AtomicInteger(0)

    @Volatile
    private var socket: WebSocket? = null

    @Volatile
    private var authenticated = false

    /**
     * What this connection listens for, and the ids the server assigned.
     *
     * `state_changed` is in there from the start rather than subscribed separately, which is
     * what stops a `trigger.ha_event` naming that type subscribing to it a *second* time —
     * Home Assistant accepts both and then delivers every state change twice. See
     * [HaSubscriptions], which owns that invariant and is tested for it.
     */
    private val subscriptions = HaSubscriptions()

    /** The id of the seeding `get_states`, so its result can be told from any other. */
    @Volatile
    private var seedId = 0

    /**
     * Commands waiting for their answer, by message id.
     *
     * Home Assistant answers every command with a `result` carrying the id it was sent with, so
     * correlation is the entire mechanism — there is no other way to tell one answer from
     * another on a single multiplexed socket.
     *
     * **Every one of these is completed on disconnect**, exceptionally, rather than left to time
     * out: a picker awaiting a service list when the socket drops should fall back at once, not
     * sit there for however long its own timeout happens to be.
     */
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<String>>()

    /** Live trigger subscriptions by message id, so a fired trigger can be routed back. */
    private val triggerSubscriptions = ConcurrentHashMap<Int, String>()

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
        socket = client.newWebSocket(Request.Builder().url(url).build(), Handler())
    }

    /** Closes the socket. Safe to call when there is none. */
    fun close() {
        authenticated = false
        socket?.close(NORMAL_CLOSURE, null)
        socket = null
        failPending("The connection to Home Assistant closed")
    }

    /**
     * Sends one command and waits for its answer, or null.
     *
     * Null covers the socket being down, Home Assistant refusing the command, and the connection
     * dropping while waiting — three failures with one answer, because every caller does the
     * same thing with them: fall back to whatever it already had. A command an older instance
     * has never heard of comes back *refused*, which is exactly the shape that makes this
     * degrade rather than break.
     */
    suspend fun request(frame: (Int) -> String): String? {
        if (!authenticated) return null
        val id = nextId()
        val waiting = CompletableDeferred<String>()
        pending[id] = waiting
        send(frame(id))
        return runCatching { withTimeoutOrNull(REQUEST_TIMEOUT_MS) { waiting.await() } }
            .getOrNull()
            .also { pending.remove(id) }
    }

    /**
     * Subscribes to one of Home Assistant's own triggers, answering the id to cancel it with.
     *
     * Null when the socket is down or the server refused — most likely a trigger type an older
     * instance has never heard of, which the caller reports into the macro's console rather than
     * retrying.
     */
    suspend fun subscribeTrigger(trigger: String, entityId: String, options: JsonObject): Int? {
        if (!authenticated) return null
        val id = nextId()
        val waiting = CompletableDeferred<String>()
        pending[id] = waiting
        triggerSubscriptions[id] = trigger
        val answered = runCatching {
            send(HaMessages.subscribeTrigger(id, trigger, entityId, options))
            withTimeoutOrNull(REQUEST_TIMEOUT_MS) { waiting.await() }
        }.getOrNull()
        pending.remove(id)
        if (answered == null) triggerSubscriptions.remove(id)
        return id.takeIf { answered != null }
    }

    /** Drops a trigger subscription taken out by [subscribeTrigger]. */
    fun unsubscribeTrigger(subscriptionId: Int) {
        if (triggerSubscriptions.remove(subscriptionId) == null) return
        send(HaMessages.unsubscribeEvents(nextId(), subscriptionId))
    }

    private fun failPending(reason: String) {
        pending.values.forEach { it.completeExceptionally(IllegalStateException(reason)) }
        pending.clear()
        triggerSubscriptions.clear()
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
        if (!subscriptions.add(eventType)) return
        if (authenticated) subscribe(eventType)
    }

    /**
     * Drops [eventType]. The last node interested in it having gone is the caller's business.
     *
     * A no-op for `state_changed` whatever the caller asks: the state cache behind
     * `value.ha_state` goes on needing it after the last trigger watching it has disarmed.
     */
    fun unwatch(eventType: String) {
        val subscriptionId = subscriptions.remove(eventType) ?: return
        send(HaMessages.unsubscribeEvents(nextId(), subscriptionId))
    }

    private fun nextId(): Int = ids.incrementAndGet()

    private fun send(frame: String) {
        socket?.send(frame)
    }

    private fun subscribe(eventType: String) {
        val id = nextId()
        subscriptions.assigned(eventType, id)
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
            failPending(t.message.orEmpty().ifBlank { "The connection to Home Assistant failed" })
            listener.onClosed(t.message.orEmpty(), permanent = false, level = LogLevel.WARN)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            authenticated = false
            socket = null
            failPending("The connection to Home Assistant closed")
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
        // One pass over everything wanted, `state_changed` included. Subscribing to that one
        // separately here is what used to deliver it twice to a node watching it — and, worse,
        // overwrote the first subscription's id so it could never be cancelled at all.
        subscriptions.onConnect().forEach { subscribe(it) }
    }

    private fun onResult(frame: HaMessages.Frame.Result) {
        pending.remove(frame.id)?.let { waiting ->
            if (frame.success) {
                waiting.complete(frame.body)
            } else {
                waiting.completeExceptionally(IllegalStateException(frame.error.ifBlank { REFUSED }))
            }
            return
        }
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
        // A trigger subscription's events carry no `event_type` at all — the message id is what
        // says which trigger fired, which is why the id is kept rather than only the type.
        if (triggerSubscriptions.containsKey(frame.id)) {
            listener.onTriggerFired(frame.id, HaMessages.triggerOf(frame.variables))
            return
        }
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

        /** What a refusal with no message of its own is called. */
        private const val REFUSED = "Home Assistant refused the request"

        /**
         * How long one command waits for its answer.
         *
         * Short, because every caller has something to fall back to and a picker that hangs is
         * worse than one that quietly shows the wider list.
         */
        private const val REQUEST_TIMEOUT_MS = 5_000L
    }
}
