package com.example.ottomatic.data.homeassistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The websocket protocol, pinned.
 *
 * The reason a websocket needs this more than a request-response API does: an auth
 * failure, a subscription confirmation, an event carrying a state change and a server
 * that closed mid-handshake cannot be produced on demand from a live instance. That is
 * `data/ai/`'s argument for testing protocols rather than transports, applied to the one
 * long-lived connection in the app.
 */
class HaMessagesTest {

    /**
     * **The handshake trap.** Every other outgoing frame carries an `id` and is rejected
     * without one — but the auth exchange happens before the id sequence begins, and
     * sending an id *here* is an error too. A naive implementation adds it for
     * consistency and never authenticates.
     */
    @Test
    fun `the auth frame carries no id`() {
        val frame = HaMessages.auth("secret-token")

        assertTrue(frame.contains("\"type\":\"auth\""))
        assertTrue(frame.contains("\"access_token\":\"secret-token\""))
        assertFalse(frame.contains("\"id\""))
    }

    @Test
    fun `a subscription names its event type and an unsubscribe names the subscription`() {
        assertTrue(HaMessages.subscribeEvents(4, "zha_event").contains("\"event_type\":\"zha_event\""))
        assertTrue(HaMessages.subscribeEvents(4, "zha_event").contains("\"id\":4"))
        assertTrue(HaMessages.unsubscribeEvents(9, 4).contains("\"subscription\":4"))
    }

    /**
     * Blank means "every event on the bus", which is the protocol's own default and is
     * used by nothing here — a busy install is hundreds of frames a minute. Left
     * expressible rather than forbidden, and pinned so the field is not sent empty.
     */
    @Test
    fun `a blank event type omits the field rather than sending an empty one`() {
        assertFalse(HaMessages.subscribeEvents(1, "").contains("event_type"))
    }

    @Test
    fun `the handshake frames are recognised`() {
        assertEquals(
            HaMessages.Frame.AuthRequired,
            HaMessages.parse("""{"type":"auth_required","ha_version":"2026.8"}"""),
        )
        assertEquals(HaMessages.Frame.AuthOk, HaMessages.parse("""{"type":"auth_ok"}"""))
        assertEquals(HaMessages.Frame.Pong, HaMessages.parse("""{"id":3,"type":"pong"}"""))
    }

    /**
     * `auth_invalid` carries the only sentence that says why, and it is the one failure
     * that must **not** be retried with backoff — re-presenting a revoked token every
     * half hour for ever is a loop, not a retry.
     */
    @Test
    fun `an auth failure keeps the servers own explanation`() {
        val frame = HaMessages.parse("""{"type":"auth_invalid","message":"Invalid access token or password"}""")

        assertEquals(HaMessages.Frame.AuthInvalid("Invalid access token or password"), frame)
    }

    @Test
    fun `a result carries its id success and error`() {
        val ok = HaMessages.parse("""{"id":1,"type":"result","success":true,"result":[]}""")
            as HaMessages.Frame.Result
        val bad = HaMessages.parse(
            """{"id":2,"type":"result","success":false,"error":{"code":"x","message":"nope"}}""",
        ) as HaMessages.Frame.Result

        assertEquals(1, ok.id)
        assertTrue(ok.success)
        assertEquals(2, bad.id)
        assertFalse(bad.success)
        assertEquals("nope", bad.error)
    }

    @Test
    fun `an event carries its subscription id type and data`() {
        val frame = HaMessages.parse(
            """{"id":7,"type":"event","event":{"event_type":"zha_event",
               "origin":"LOCAL","data":{"command":"on"}}}""",
        ) as HaMessages.Frame.Event

        assertEquals(7, frame.id)
        assertEquals("zha_event", frame.eventType)
        assertEquals("LOCAL", frame.origin)
        assertEquals("on", frame.data["command"]?.toString()?.trim('"'))
    }

    @Test
    fun `a state change carries both sides`() {
        val frame = HaMessages.parse(
            """{"id":2,"type":"event","event":{"event_type":"state_changed","data":{
               "entity_id":"binary_sensor.front_door",
               "old_state":{"entity_id":"binary_sensor.front_door","state":"off","attributes":{}},
               "new_state":{"entity_id":"binary_sensor.front_door","state":"on",
                            "attributes":{"friendly_name":"Front door"}}}}}""",
        ) as HaMessages.Frame.Event

        val (previous, current) = HaMessages.stateChange(frame.data)

        assertEquals("off", previous?.state)
        assertEquals("on", current?.state)
        assertEquals("Front door", current?.friendlyName)
    }

    /**
     * Both are ordinary rather than exceptional: `old_state` is null for an entity that
     * has just appeared — every entity, on the first restart after adding an integration
     * — and `new_state` is null for one that has just been removed.
     */
    @Test
    fun `a new entity has no previous state and a removed one has no current`() {
        val appeared = HaMessages.parse(
            """{"id":2,"type":"event","event":{"event_type":"state_changed","data":{
               "entity_id":"sensor.x","old_state":null,
               "new_state":{"entity_id":"sensor.x","state":"1","attributes":{}}}}}""",
        ) as HaMessages.Frame.Event
        val removed = HaMessages.parse(
            """{"id":2,"type":"event","event":{"event_type":"state_changed","data":{
               "entity_id":"sensor.x",
               "old_state":{"entity_id":"sensor.x","state":"1","attributes":{}},
               "new_state":null}}}""",
        ) as HaMessages.Frame.Event

        assertNull(HaMessages.stateChange(appeared.data).first)
        assertEquals("1", HaMessages.stateChange(appeared.data).second?.state)
        assertNull(HaMessages.stateChange(removed.data).second)
    }

    /**
     * Home Assistant adds message types between releases. A socket that died on one it
     * had not been taught would break on a server upgrade the user did not connect to
     * this app at all.
     */
    @Test
    fun `an unknown or unparseable frame is ignored rather than fatal`() {
        assertEquals(HaMessages.Frame.Unknown, HaMessages.parse("""{"type":"something_new_in_2027"}"""))
        assertEquals(HaMessages.Frame.Unknown, HaMessages.parse("not json at all"))
        assertEquals(HaMessages.Frame.Unknown, HaMessages.parse(""))
        // An event frame with no event object in it.
        assertEquals(HaMessages.Frame.Unknown, HaMessages.parse("""{"id":1,"type":"event"}"""))
    }

    @Test
    fun `the seed result parses back into states`() {
        val states = HaMessages.statesOf(
            """[{"entity_id":"light.a","state":"on","attributes":{"friendly_name":"A"}}]""",
        )

        assertEquals(1, states.size)
        assertEquals("light.a", states.single().entityId)
    }

    // ---- The trigger platform ----

    /**
     * The exact frame the web interface's own automation editor sends, pinned because the app's
     * trigger list is only as right as this: the entity goes in a `target` object rather than
     * beside the type, and `expand_group` is what makes a trigger on a group of speakers mean
     * anything.
     */
    @Test
    fun `the trigger list is asked for with a target rather than a bare entity`() {
        val frame = HaMessages.triggersForTarget(68, "media_player.ultra")

        assertTrue(frame.contains("\"type\":\"get_triggers_for_target\""))
        assertTrue(frame.contains("\"target\":{\"entity_id\":\"media_player.ultra\"}"))
        assertTrue(frame.contains("\"expand_group\":true"))
        assertTrue(frame.contains("\"id\":68"))
    }

    /**
     * The answer is a flat array of ids, and anything else reads as **empty meaning "cannot
     * narrow"** rather than as a failure — an instance too old for the command answers a
     * refusal, and a picker must widen rather than empty.
     */
    @Test
    fun `a list of ids is read, and anything else reads as empty`() {
        assertEquals(
            listOf("media_player.turned_on", "media_player.paused_playing"),
            HaMessages.stringsOf("""["media_player.turned_on","media_player.paused_playing"]"""),
        )
        assertTrue(HaMessages.stringsOf("").isEmpty())
        assertTrue(HaMessages.stringsOf("""{"error":"unknown command"}""").isEmpty())
    }

    /**
     * The **options are merged into the trigger object**, not nested under a key of their own —
     * `for` and `behavior` are siblings of `trigger` and `target` in Home Assistant's schema,
     * and a nested one is rejected as an unknown field, taking the whole subscription with it.
     */
    @Test
    fun `a trigger subscription names the trigger, its target and its options together`() {
        val frame = HaMessages.subscribeTrigger(
            id = 4,
            trigger = "binary_sensor.opened",
            entityId = "binary_sensor.front_door",
            options = kotlinx.serialization.json.Json.parseToJsonElement("""{"for":"00:01:30"}""")
                .let { it as kotlinx.serialization.json.JsonObject },
        )

        assertTrue(frame.contains("\"type\":\"subscribe_trigger\""))
        assertTrue(frame.contains("\"trigger\":\"binary_sensor.opened\""))
        assertTrue(frame.contains("\"target\":{\"entity_id\":\"binary_sensor.front_door\"}"))
        assertTrue(frame.contains("\"for\":\"00:01:30\""))
    }

    /**
     * **A fired trigger arrives on a different key from an event**, and reading the wrong one
     * gives an event that parses perfectly and carries nothing: the bus publishes `data` and
     * names its type, where a trigger publishes `variables.trigger` and names nothing at all.
     */
    @Test
    fun `a fired trigger carries its payload under variables`() {
        val frame = HaMessages.parse(
            """
            {"id":7,"type":"event","event":{"variables":{"trigger":
              {"entity_id":"binary_sensor.front_door","from_state":{"state":"off"}}}}}
            """.trimIndent(),
        )

        val event = frame as HaMessages.Frame.Event
        assertEquals(7, event.id)
        // No event_type at all — the id is the whole of the routing information.
        assertEquals("", event.eventType)

        val trigger = HaMessages.triggerOf(event.variables)
        assertEquals("binary_sensor.front_door", HaMessages.field(trigger, "entity_id"))
        assertEquals("off", HaMessages.nestedState(trigger, "from_state"))
        // A trigger platform that publishes nothing of the sort is ordinary, not a failure.
        assertEquals("", HaMessages.nestedState(trigger, "to_state"))
        assertTrue(HaMessages.triggerOf(event.data).isEmpty())
    }
}
