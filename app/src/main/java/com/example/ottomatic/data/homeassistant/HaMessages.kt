package com.example.ottomatic.data.homeassistant

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

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
// One function per frame the protocol defines, in each direction. The wire sets the count,
// and splitting it would put half of one conversation in another file.
@Suppress("TooManyFunctions")
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
     * Asks for every service, **with its full description**.
     *
     * The REST `/api/services` does not carry `target` or the per-field `selector`s — it is a
     * list of names and descriptions and nothing more. Everything that makes a service form
     * possible is only here, which is why the app opens a socket to ask rather than using the
     * endpoint that looks like it would do.
     */
    fun getServices(id: Int): String = encode {
        put(ID, id)
        put(TYPE, "get_services")
    }

    /**
     * Asks which triggers apply to one entity.
     *
     * The command Home Assistant's own automation editor uses, and the reason this app stopped
     * deriving a state list of its own: what belongs on a media player is
     * `media_player.started_playing` and `media_player.volume_crossed_threshold`, which are not
     * states at all and which no amount of reading `/api/states` could ever produce.
     *
     * `expand_group` asks Home Assistant to look through a group entity to its members, which is
     * what makes a trigger on "the living room speakers" mean anything.
     */
    fun triggersForTarget(id: Int, entityId: String): String = encode {
        put(ID, id)
        put(TYPE, "get_triggers_for_target")
        putJsonObject(TARGET) { put(ENTITY_ID, entityId) }
        put("expand_group", true)
    }

    /** Asks which services apply to one entity. [triggersForTarget]'s sibling. */
    fun servicesForTarget(id: Int, entityId: String): String = encode {
        put(ID, id)
        put(TYPE, "get_services_for_target")
        putJsonObject(TARGET) { put(ENTITY_ID, entityId) }
        put("expand_group", true)
    }

    /**
     * Subscribes to one of Home Assistant's own triggers, so **it** decides when to fire.
     *
     * The alternative — watching `state_changed` and re-implementing each trigger's meaning —
     * is what this replaces, and it could never have been complete: `volume_crossed_threshold`
     * is not a state transition, and `for` and `behavior` are evaluation rules a client would
     * have to reproduce exactly to agree with what the user sees in the web interface.
     *
     * [options] is passed through as the trigger's own options object — `for`, `behavior` and
     * whatever else that trigger type declares.
     */
    fun subscribeTrigger(id: Int, trigger: String, entityId: String, options: JsonObject): String = encode {
        put(ID, id)
        put(TYPE, "subscribe_trigger")
        putJsonObject(TRIGGER) {
            put(TRIGGER, trigger)
            putJsonObject(TARGET) { put(ENTITY_ID, entityId) }
            options.forEach { (key, value) -> put(key, value) }
        }
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

        /**
         * An event on a subscription, matched by [id].
         *
         * [variables] is what a **trigger** subscription delivers, and it is a different key
         * rather than a different shape of the same one: an event-bus frame carries `data` and
         * names its type, where a fired trigger carries `variables.trigger` and names nothing —
         * the message id is the only thing saying which trigger it was. Both are read here so a
         * caller does not have to know which kind of subscription it is looking at.
         */
        data class Event(
            val id: Int,
            val eventType: String,
            val data: JsonObject,
            val origin: String,
            val variables: JsonObject = JsonObject(emptyMap()),
        ) : Frame

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
                    variables = event[VARIABLES] as? JsonObject ?: JsonObject(emptyMap()),
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

    /**
     * What a fired trigger says about itself.
     *
     * `variables.trigger` on a trigger subscription's event, which carries whatever that
     * trigger platform chose to publish — `entity_id` and `from_state`/`to_state` for the ones
     * built on a state change, and rather less for the ones that are not. Empty when the frame
     * is not shaped that way, which every caller treats as *nothing extra to say* rather than
     * as a failure: the fact that it fired at all is the part that matters.
     */
    fun triggerOf(variables: JsonObject): JsonObject = variables[TRIGGER] as? JsonObject ?: JsonObject(emptyMap())

    /** One string field of a trigger's own payload, or blank. */
    fun field(obj: JsonObject, key: String): String = obj.str(key)

    /** The `state` of a nested `from_state`/`to_state` object, or blank. */
    fun nestedState(obj: JsonObject, key: String): String = (obj[key] as? JsonObject)?.str("state").orEmpty()

    /** The states in a `get_states` result body. */
    fun statesOf(resultBody: String): List<HaResources.HaState> = HaResources.parseStates(resultBody)

    /**
     * The plain strings in a result body that is a JSON array of them.
     *
     * What `get_triggers_for_target` and `get_services_for_target` answer with: a flat list of
     * ids such as `media_player.started_playing`. Anything unparseable reads as empty, which
     * every caller treats as *cannot narrow* rather than *nothing applies*.
     */
    fun stringsOf(resultBody: String): List<String> = runCatching {
        Json.parseToJsonElement(resultBody).jsonArray.mapNotNull { it.jsonPrimitive.contentOrNull }
    }.getOrDefault(emptyList())

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
    private const val VARIABLES = "variables"
    private const val TARGET = "target"
    private const val TRIGGER = "trigger"
    private const val ENTITY_ID = "entity_id"

    /** The event every connection subscribes to, unconditionally. */
    const val STATE_CHANGED = "state_changed"
}
