package com.example.ottomatic.data.homeassistant

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Every frame the Home Assistant websocket protocol sends and receives.
 *
 * **Pure and JVM-tested**, which for a websocket is the whole point: the interesting
 * half is *what a frame means*, and an auth failure, a subscription confirmation and an
 * event carrying a state change cannot be produced on demand from a live server. That
 * is `data/ai/`'s argument for testing protocols rather than transports, applied to the
 * one place in this app where the transport is a long-lived connection.
 *
 * Two details of the handshake are not obvious and break a naive implementation:
 *
 * - **The `auth` frame carries no `id`.** Every other outgoing frame does, and the
 *   server rejects one that omits it — but the auth exchange happens *before* the id
 *   sequence begins, and sending an id there is an error too.
 * - **Ids restart at 1 on every connection.** They must strictly increase *within* a
 *   connection, and a reconnect is a new one. Carrying a counter across a reconnect
 *   works by accident and then fails the day the server checks.
 */
internal object HaMessages {

    private val json = Json { encodeDefaults = true }

    // ---- Outgoing ----

    /**
     * The reply to `auth_required`.
     *
     * No `id` field, deliberately — see the class KDoc. This is the only frame in the
     * protocol without one.
     */
    fun auth(token: String): String = encode {
        put(TYPE, AUTH)
        put("access_token", token)
    }

    /** Asks for the current state of everything, to seed the cache. */
    fun getStates(id: Int): String = encode {
        put(ID, id)
        put(TYPE, "get_states")
    }

    /**
     * Subscribes to an event type, or to every event when [eventType] is blank.
     *
     * Blank is used for nothing today and is left expressible because it is the
     * protocol's own default; every caller here names a type, because subscribing to
     * the whole bus on a busy install is hundreds of frames a minute.
     */
    fun subscribeEvents(id: Int, eventType: String): String = encode {
        put(ID, id)
        put(TYPE, "subscribe_events")
        if (eventType.isNotBlank()) put("event_type", eventType)
    }

    /** Drops the subscription created by the frame with id [subscriptionId]. */
    fun unsubscribeEvents(id: Int, subscriptionId: Int): String = encode {
        put(ID, id)
        put(TYPE, "unsubscribe_events")
        put("subscription", subscriptionId)
    }

    // ---- Incoming ----

    /** What arrived. */
    sealed interface Frame {
        /** The server is asking for a token. Nothing else may be sent until it is. */
        data object AuthRequired : Frame

        /** Authenticated. The id sequence starts now. */
        data object AuthOk : Frame

        /** The token was refused. **Not** something to retry with backoff. */
        data class AuthInvalid(val message: String) : Frame

        /** The answer to a request, matched by [id]. */
        data class Result(val id: Int, val success: Boolean, val error: String, val body: String) : Frame

        /** An event on a subscription, matched by [id]. */
        data class Event(val id: Int, val eventType: String, val data: JsonObject, val origin: String) : Frame

        /** The server's own keepalive. */
        data object Pong : Frame

        /** Something this build does not know. Ignored rather than fatal. */
        data object Unknown : Frame
    }

    /**
     * Reads one frame.
     *
     * Anything unparseable or unrecognised is [Frame.Unknown] rather than an exception:
     * Home Assistant adds message types between releases, and a socket that died on one
     * it had not been taught would break on a server upgrade the user did not connect
     * to this app at all.
     */
    fun parse(text: String): Frame = runCatching {
        val obj = Json.parseToJsonElement(text).jsonObject
        when (obj.str(TYPE)) {
            "auth_required" -> Frame.AuthRequired
            "auth_ok" -> Frame.AuthOk
            "auth_invalid" -> Frame.AuthInvalid(obj.str("message"))
            "pong" -> Frame.Pong
            "result" -> Frame.Result(
                id = obj.int(ID),
                success = obj[SUCCESS]?.jsonPrimitive?.booleanOrNull ?: false,
                error = (obj[ERROR] as? JsonObject)?.str("message").orEmpty(),
                body = obj[RESULT]?.toString().orEmpty(),
            )
            "event" -> (obj[EVENT] as? JsonObject)?.let { event ->
                Frame.Event(
                    id = obj.int(ID),
                    eventType = event.str("event_type"),
                    data = event[DATA] as? JsonObject ?: JsonObject(emptyMap()),
                    origin = event.str("origin"),
                )
            } ?: Frame.Unknown
            else -> Frame.Unknown
        }
    }.getOrDefault(Frame.Unknown)

    /**
     * The two states either side of a `state_changed` event.
     *
     * `old_state` is **null for an entity that has just appeared**, and `new_state` is
     * null for one that has just been removed. Both are ordinary rather than
     * exceptional, which is why this is a pair of nullables and not a failure.
     */
    fun stateChange(data: JsonObject): Pair<HaResources.HaState?, HaResources.HaState?> {
        fun stateOf(key: String) = (data[key] as? JsonObject)?.let { obj ->
            obj.str("entity_id").takeIf { it.isNotBlank() }?.let { entityId ->
                HaResources.HaState(
                    entityId = entityId,
                    state = obj.str("state"),
                    attributes = obj["attributes"] as? JsonObject ?: JsonObject(emptyMap()),
                )
            }
        }
        return stateOf("old_state") to stateOf("new_state")
    }

    /** The states in a `get_states` result body. */
    fun statesOf(resultBody: String): List<HaResources.HaState> = HaResources.parseStates(resultBody)

    private fun encode(build: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): String =
        json.encodeToString(JsonObject.serializer(), buildJsonObject(build))

    private fun JsonObject.str(key: String): String = this[key]?.jsonPrimitive?.contentOrNull.orEmpty()

    private fun JsonObject.int(key: String): Int = this[key]?.jsonPrimitive?.intOrNull ?: 0

    private const val TYPE = "type"
    private const val ID = "id"
    private const val AUTH = "auth"
    private const val SUCCESS = "success"
    private const val ERROR = "error"
    private const val RESULT = "result"
    private const val EVENT = "event"
    private const val DATA = "data"

    /** The event every connection subscribes to, unconditionally. */
    const val STATE_CHANGED = "state_changed"
}
