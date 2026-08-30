package io.github.m1n1m1.easymatic.data.homeassistant

import io.github.m1n1m1.easymatic.core.service.SmartHomeTargetKind
import io.github.m1n1m1.easymatic.domain.model.HaSelector
import io.github.m1n1m1.easymatic.domain.model.HaService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What Home Assistant reports, turned into what the pickers list.
 *
 * `HueResourcesTest`'s counterpart, and it exists for the same reason: every one of
 * these failures is **silent**. A light filed under no area, an area listed with nothing
 * controllable in it, a colour capability read off the wrong attribute — none of them
 * throws, and all of them produce a node that looks right and does nothing.
 */
class HaResourcesTest {

    private val states = """
        [
          {"entity_id": "light.kitchen_ceiling", "state": "on",
           "attributes": {"friendly_name": "Ceiling", "supported_color_modes": ["color_temp", "xy"]}},
          {"entity_id": "light.kitchen_strip", "state": "off",
           "attributes": {"friendly_name": "Strip", "supported_color_modes": ["brightness"]}},
          {"entity_id": "light.garage_bulb", "state": "off",
           "attributes": {"friendly_name": "Garage bulb", "supported_color_modes": ["color_temp"]}},
          {"entity_id": "sensor.hall_temperature", "state": "21.4",
           "attributes": {"friendly_name": "Hall temperature", "unit_of_measurement": "°C",
                          "device_class": "temperature"}},
          {"entity_id": "binary_sensor.front_door", "state": "off",
           "attributes": {"friendly_name": "Front door", "device_class": "door"}},
          {"entity_id": "scene.dinner", "state": "unknown", "attributes": {"friendly_name": "Dinner"}}
        ]
    """.trimIndent()

    private val areas = """
        [
          {"id": "kitchen", "name": "Kitchen",
           "entities": ["light.kitchen_ceiling", "light.kitchen_strip", "scene.dinner"]},
          {"id": "hall", "name": "Hall", "entities": ["sensor.hall_temperature"]},
          {"id": "garage", "name": "Garage", "entities": ["light.garage_bulb"]}
        ]
    """.trimIndent()

    private fun resources() =
        HaResources.resourcesOf(HaResources.parseStates(states), HaResources.parseAreas(areas))

    @Test
    fun `every state is read with its attributes`() {
        val parsed = HaResources.parseStates(states)

        assertEquals(6, parsed.size)
        val sensor = parsed.first { it.entityId == "sensor.hall_temperature" }
        assertEquals("21.4", sensor.state)
        assertEquals("Hall temperature", sensor.friendlyName)
        assertEquals("sensor", sensor.domain)
    }

    /**
     * A light with no `friendly_name` still has to be pickable, and the entity id is
     * the only name there is. Falling back to blank would put an unlabelled row in the
     * picker that could not be told from its neighbours.
     */
    @Test
    fun `an entity with no friendly name falls back to its id`() {
        val parsed = HaResources.parseStates("""[{"entity_id": "light.x", "state": "on", "attributes": {}}]""")

        assertEquals("light.x", parsed.single().friendlyName)
    }

    /**
     * The mapping most likely to be got wrong, because getting it wrong produces a node
     * that reports success and changes nothing — `SmartHomeResource.rid`'s KDoc says so
     * about the Hue side, and it is just as true here.
     */
    @Test
    fun `an area becomes a group addressed by its area id`() {
        val kitchen = resources().single { it.kind == SmartHomeTargetKind.GROUP && it.name == "Kitchen" }

        assertEquals("area:kitchen", kitchen.rid)
        assertEquals(listOf("light.kitchen_ceiling", "light.kitchen_strip"), kitchen.memberRids)
    }

    /**
     * A scene in the same area is **not** a member: `onlyIfOn` reads this list to work
     * out which lights to ask about, and a scene has no on-ness to ask for.
     */
    @Test
    fun `a group lists only the lights in its area`() {
        val kitchen = resources().single { it.kind == SmartHomeTargetKind.GROUP && it.name == "Kitchen" }

        assertFalse(kitchen.memberRids.contains("scene.dinner"))
    }

    /**
     * `HueResources` drops a room with no `grouped_light` service for this reason, and
     * a Home Assistant household has far more areas that are not about lights — "Hall"
     * holding one thermometer is entirely normal. Listing it would offer a target that
     * can never do anything.
     */
    @Test
    fun `an area with no light in it is dropped rather than listed`() {
        val names = resources().filter { it.kind == SmartHomeTargetKind.GROUP }.map { it.name }

        assertEquals(listOf("Kitchen", "Garage"), names)
    }

    /**
     * Home Assistant has areas and no zones, so every area belongs in the picker's
     * Rooms section. `SmartHomeResource.ZONE` marks a Hue concept with no counterpart
     * here, and writing it would file every Home Assistant area under "Zones".
     */
    @Test
    fun `an area is a room and never a zone`() {
        val groups = resources().filter { it.kind == SmartHomeTargetKind.GROUP }

        assertTrue(groups.all { it.room.isEmpty() })
    }

    @Test
    fun `a light carries the area it belongs to`() {
        val ceiling = resources().single { it.kind == SmartHomeTargetKind.LIGHT && it.rid == "light.kitchen_ceiling" }

        assertEquals("Kitchen", ceiling.room)
        assertEquals("Ceiling", ceiling.name)
    }

    /**
     * Read from `supported_color_modes`, never from whether `rgb_color` happens to be
     * present: a colour bulb sitting in white mode reports no `rgb_color` at all, and
     * reading capability off that would grey out Set colour on a light that supports it.
     */
    @Test
    fun `colour and temperature support come from the declared modes`() {
        val lights = resources().filter { it.kind == SmartHomeTargetKind.LIGHT }.associateBy { it.rid }

        with(lights.getValue("light.kitchen_ceiling")) {
            assertTrue(supportsColour)
            assertTrue(supportsTemperature)
        }
        with(lights.getValue("light.garage_bulb")) {
            assertFalse(supportsColour)
            assertTrue(supportsTemperature)
        }
        // Brightness-only: neither, and the picker says "White only".
        with(lights.getValue("light.kitchen_strip")) {
            assertFalse(supportsColour)
            assertFalse(supportsTemperature)
        }
    }

    /**
     * Turning a scene off means switching off the area behind it, and the template
     * already said which that is — so unlike Hue's, this is never blank for a scene in
     * an area and the network fallback has no counterpart here.
     */
    @Test
    fun `a scene carries the area behind it`() {
        val dinner = resources().single { it.kind == SmartHomeTargetKind.SCENE }

        assertEquals("scene.dinner", dinner.rid)
        assertEquals("Kitchen", dinner.room)
        assertEquals("area:kitchen", dinner.groupRid)
    }

    @Test
    fun `a scene in no area has no group behind it`() {
        val resources = HaResources.resourcesOf(
            HaResources.parseStates("""[{"entity_id": "scene.x", "state": "unknown", "attributes": {}}]"""),
            emptyList(),
        )

        assertEquals("", resources.single().groupRid)
    }

    /**
     * The entity list is the wider one: everything, because a thermostat and a door
     * sensor are the whole reason to have a Home Assistant hub, and a light is in
     * **both** lists because it is both a thing to switch and a thing to read.
     */
    @Test
    fun `every entity is listed with its unit and device class`() {
        val entities = HaResources.entitiesOf(HaResources.parseStates(states), HaResources.parseAreas(areas))

        assertEquals(6, entities.size)
        with(entities.single { it.entityId == "sensor.hall_temperature" }) {
            assertEquals("sensor", domain)
            assertEquals("°C", unit)
            assertEquals("temperature", deviceClass)
            assertEquals("Hall", area)
        }
        assertTrue(entities.any { it.entityId == "light.kitchen_ceiling" })
    }

    @Test
    fun `services are read out of the keys of each domain`() {
        val payload = """
            [
              {"domain": "light", "services": {
                 "turn_on": {"name": "Turn on", "description": "Turns a light on"},
                 "toggle": {"name": "Toggle", "description": ""}}},
              {"domain": "climate", "services": {"set_temperature": {"name": "Set target temperature"}}}
            ]
        """.trimIndent()

        val services = HaResources.parseServices(payload).associateBy { it.id }

        assertEquals(3, services.size)
        assertEquals("Turn on", services.getValue("light.turn_on").name)
        assertEquals("light", services.getValue("light.toggle").domain)
        assertEquals("set_temperature", services.getValue("climate.set_temperature").service)
    }

    /**
     * **The shape that empties the list for a whole class of installs if it is got wrong.**
     * Current Home Assistant writes `entity` as an array of filters whose `domain` is itself an
     * array; older instances and some custom integrations write an object at the outer level
     * and a bare string at the inner one. Both must parse, or those users see a chooser that
     * silently offers nothing.
     */
    @Test
    fun `a target entity filter parses in both the array and the legacy object shape`() {
        val modern = """
            [{"domain": "light", "services": {"turn_on": {
              "target": {"entity": [{"domain": ["light", "switch"]}]}}}}]
        """.trimIndent()
        val legacy = """
            [{"domain": "light", "services": {"turn_on": {
              "target": {"entity": {"domain": "light"}}}}}]
        """.trimIndent()

        assertEquals(listOf("light", "switch"), HaResources.parseServices(modern).single().targetDomains)
        assertEquals(listOf("light"), HaResources.parseServices(legacy).single().targetDomains)
    }

    /**
     * The three-way distinction the blank-entity rule rests on, and the reason `takesTarget`
     * and `targetDomains` are two fields rather than one.
     */
    @Test
    fun `an absent target is not the same as an unconstrained one`() {
        val payload = """
            [{"domain": "homeassistant", "services": {
               "restart": {"name": "Restart"},
               "turn_on": {"target": {"entity": [{}]}},
               "update_entity": {"target": {}}}}]
        """.trimIndent()

        val services = HaResources.parseServices(payload).associateBy { it.id }

        // No target key at all: acts on no entity.
        assertFalse(services.getValue("homeassistant.restart").takesTarget)
        // A filter naming no domain means *any* entity, not none.
        assertTrue(services.getValue("homeassistant.turn_on").takesTarget)
        assertTrue(services.getValue("homeassistant.turn_on").targetDomains.isEmpty())
        assertTrue(services.getValue("homeassistant.update_entity").takesTarget)
    }

    /**
     * Which services a chooser offers, and the **degradation rule** that keeps it honest: a
     * service carrying no target metadata is offered always, because empty metadata narrows
     * nothing and must never narrow to nothing.
     */
    @Test
    fun `a service is offered for the domains its target accepts`() {
        val payload = """
            [{"domain": "light", "services": {
               "turn_on": {"target": {"entity": [{"domain": ["light"]}]}},
               "restart": {},
               "any": {"target": {"entity": [{}]}}}}]
        """.trimIndent()
        val services = HaResources.parseServices(payload).associateBy { it.id }

        with(services.getValue("light.turn_on")) {
            assertTrue(offersFor("light"))
            assertFalse(offersFor("lock"))
            // Nothing chosen yet: a service that needs an entity is not the answer.
            assertFalse(offersFor(""))
        }
        with(services.getValue("light.restart")) {
            // Takes no entity, so it is exactly what a blank entity should show — and it is
            // still offered once one is chosen, because it ignores the entity rather than
            // conflicting with it. Hiding it there would mean clearing the entity to reach
            // `homeassistant.restart`, which is a dead end for no gain.
            assertTrue(offersFor(""))
            assertTrue(offersFor("light"))
        }
        with(services.getValue("light.any")) {
            assertTrue(offersFor("lock"))
        }
    }

    /** An old snapshot has no target metadata at all, and must offer everything. */
    @Test
    fun `a service with no metadata is offered whatever the entity`() {
        val bare = HaService(domain = "light", service = "turn_on")

        assertTrue(bare.offersFor("light"))
        assertTrue(bare.offersFor("lock"))
        assertTrue(bare.offersFor(""))
    }

    /**
     * Home Assistant 2024.8+ groups advanced fields into a collapsible section whose value
     * carries its own `fields`. Not flattening it makes the form offer `advanced_fields` where
     * the user needed `profile`.
     */
    @Test
    fun `a collapsible field section is flattened one level`() {
        val payload = """
            [{"domain": "light", "services": {"turn_on": {"fields": {
               "brightness_pct": {"required": true, "selector": {"number": {"min": 0, "max": 100}}},
               "advanced_fields": {"collapsed": true, "fields": {
                  "profile": {"selector": {"text": null}}}}}}}}]
        """.trimIndent()

        val fields = HaResources.parseServices(payload).single().fields

        assertEquals(listOf("brightness_pct", "profile"), fields.map { it.name })
        // Required first, because the hint's whole job is to say what the box needs.
        assertTrue(fields.first().required)
    }

    @Test
    fun `each selector kind is read, and an unknown one is not generated`() {
        val payload = """
            [{"domain": "x", "services": {"y": {"fields": {
               "mode":   {"selector": {"select": {"options": ["a", "b"]}}},
               "level":  {"selector": {"number": {"min": 1, "max": 5, "step": 2}}},
               "flag":   {"selector": {"boolean": null}},
               "who":    {"selector": {"entity": {"domain": "light"}}},
               "note":   {"selector": {"text": null}},
               "colour": {"selector": {"color_rgb": null}}}}}}]
        """.trimIndent()

        val fields = HaResources.parseServices(payload).single().fields.associateBy { it.name }

        assertEquals(HaSelector.Options(listOf("a", "b")), fields.getValue("mode").selector)
        assertEquals(HaSelector.Number(1.0, 5.0, 2.0), fields.getValue("level").selector)
        assertEquals(HaSelector.Toggle, fields.getValue("flag").selector)
        assertEquals(HaSelector.Entity(listOf("light")), fields.getValue("who").selector)
        assertEquals(HaSelector.Text, fields.getValue("note").selector)
        // A selector this build has never been taught: recorded, but nothing is generated for
        // it, and the raw JSON box stays as the escape hatch.
        assertEquals(HaSelector.Unknown, fields.getValue("colour").selector)
    }

    /**
     * What a form field can offer without a round trip: the entity's own attribute names and
     * the reading it had at Refresh.
     */
    @Test
    fun `an entity carries its attributes and its reading`() {
        val states = HaResources.parseStates(
            """
            [
              {"entity_id": "input_select.mode", "state": "Home",
               "attributes": {"friendly_name": "Mode", "options": ["Home", "Away"]}},
              {"entity_id": "climate.hall", "state": "heat",
               "attributes": {"hvac_modes": ["off", "heat"]}},
              {"entity_id": "sensor.temperature", "state": "21.4",
               "attributes": {"unit_of_measurement": "°C"}}
            ]
            """.trimIndent(),
        )

        val entities = HaResources.entitiesOf(states, emptyList()).associateBy { it.entityId }

        assertEquals(listOf("friendly_name", "options"), entities.getValue("input_select.mode").attributes)
        assertEquals("heat", entities.getValue("climate.hall").state)
        assertEquals("21.4", entities.getValue("sensor.temperature").state)
        assertEquals(listOf("unit_of_measurement"), entities.getValue("sensor.temperature").attributes)
    }

    /**
     * A locked-down instance can refuse the template endpoint, and a proxy can return
     * HTML for anything. Neither may take the states read down with it — the pickers
     * degrade to ungrouped, which beats "nothing has been read yet" by a long way.
     */
    @Test
    fun `a malformed payload reads as nothing rather than throwing`() {
        assertTrue(HaResources.parseStates("<html>not found</html>").isEmpty())
        assertTrue(HaResources.parseAreas("").isEmpty())
        assertTrue(HaResources.parseServices("{}").isEmpty())
        assertTrue(HaResources.parseStates("""[{"state": "on"}]""").isEmpty())
    }

    /**
     * The two shapes the same services come in, and the reason both are read here.
     *
     * REST `/api/services` wraps them in an array of `{domain, services}`; the websocket
     * `get_services` answers one object keyed by domain. Only the second carries `target` and
     * the field `selector`s, which is why the socket is preferred — and why a snapshot taken
     * from the endpoint narrows nothing and grows no fields rather than misbehaving.
     */
    @Test
    fun `services parse from the websocket shape as well as the REST one`() {
        val socketShape = """
            {"light": {"turn_on": {"name": "Turn on",
              "target": {"entity": [{"domain": ["light"]}]},
              "fields": {"brightness_pct": {"required": true, "selector": {"number": {"min": 0, "max": 100}}}}}}}
        """.trimIndent()

        val service = HaResources.parseServices(socketShape).single()

        assertEquals("light.turn_on", service.id)
        assertEquals("Turn on", service.name)
        assertTrue(service.takesTarget)
        assertEquals(listOf("light"), service.targetDomains)
        assertEquals(HaSelector.Number(0.0, 100.0), service.fields.single().selector)
    }

    /**
     * The REST answer, which is what a refresh taken before any socket is up falls back to. It
     * is deliberately **not** an error that everything narrowing depends on is missing from it:
     * empty metadata narrows nothing, so the picker shows the wide list and the JSON box stays
     * as the way to say what a generated field would have said.
     */
    @Test
    fun `the REST shape parses with no target and no selectors, and that is not a failure`() {
        val restShape = """[{"domain": "homeassistant", "services": {"restart": {"name": "Restart"}}}]"""

        val service = HaResources.parseServices(restShape).single()

        assertEquals("homeassistant.restart", service.id)
        assertFalse(service.takesTarget)
        assertTrue(service.targetDomains.isEmpty())
        assertTrue(service.fields.isEmpty())
        // The rule that keeps it usable: a service with no target metadata is offered whatever
        // entity is in scope, rather than filtered away by a domain it never named.
        assertTrue(service.offersFor("media_player"))
    }

    @Test
    fun `an area rid round-trips and an entity id is not one`() {
        assertEquals("kitchen", HaIds.areaIdOf(HaIds.areaRid("kitchen")))
        assertTrue(HaIds.isArea("area:kitchen"))
        assertFalse(HaIds.isArea("light.kitchen_ceiling"))
        assertNull(HaIds.areaIdOf("light.kitchen_ceiling"))
        // A prefix with nothing after it names no area and must not read as one.
        assertNull(HaIds.areaIdOf("area:"))
    }
}
