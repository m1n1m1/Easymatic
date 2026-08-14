package com.example.ottomatic.domain.model

import com.example.ottomatic.core.service.SmartHomeTargetKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The Home Assistant reference, pinned in both directions.
 *
 * `SmartHomeRefTest`'s cases with one addition that is the whole difference between the
 * two specs: **a blank id is legal here and is not there**, because a bare hub
 * reference is a real thing to store — it is what `trigger.ha_event` points at, where
 * there is no entity and the hub itself is the answer.
 */
class HomeAssistantRefTest {

    @Test
    fun `a formatted reference parses back to what it named`() {
        val spec = HomeAssistantRef.format("hub-1", "sensor.hall_temperature", "Hall temperature")
        val parsed = HomeAssistantRef.parse(spec)!!

        assertEquals("hub-1", parsed.hubId)
        assertEquals("sensor.hall_temperature", parsed.id)
        assertEquals("Hall temperature", parsed.name)
    }

    /**
     * A friendly name is whatever the user called it in Home Assistant, so it can
     * contain anything at all. The two fields before it cannot contain a separator —
     * a UUID, and an entity id Home Assistant restricts to lowercase letters, digits
     * and underscores around a single dot — which is what makes the limit safe.
     */
    @Test
    fun `a name containing the separator survives the round trip`() {
        val spec = HomeAssistantRef.format("hub-1", "light.kitchen", "Kitchen | ceiling")

        assertEquals("Kitchen | ceiling", HomeAssistantRef.parse(spec)!!.name)
    }

    /**
     * The one place this parser deliberately parts company with [SmartHomeRef]: a hub
     * reference names no entity, and refusing it would leave `trigger.ha_event` with
     * nothing it could store.
     */
    @Test
    fun `a hub reference names no entity and still parses`() {
        val parsed = HomeAssistantRef.parse(HomeAssistantRef.formatHub("hub-1", "Home Assistant"))!!

        assertEquals("hub-1", parsed.hubId)
        assertEquals("", parsed.id)
        assertEquals("Home Assistant", parsed.name)
    }

    @Test
    fun `a service reference splits into its domain and service`() {
        val parsed = HomeAssistantRef.parse(HomeAssistantRef.format("hub-1", "light.turn_on", "Turn on"))!!

        assertEquals("light", parsed.domain)
        assertEquals("turn_on", parsed.service)
    }

    /**
     * An id with no dot in it is not a service, and asking for its halves must not
     * silently answer the whole string — which is what `substringBefore`'s default
     * would do, and would turn a malformed reference into a call to a service named
     * after the domain.
     */
    @Test
    fun `an id with no dot yields no domain and no service`() {
        val parsed = HomeAssistantRef.parse(HomeAssistantRef.format("hub-1", "nonsense", "?"))!!

        assertEquals("", parsed.domain)
        assertEquals("", parsed.service)
    }

    @Test
    fun `anything malformed parses to nothing at all`() {
        assertNull(HomeAssistantRef.parse(""))
        // No prefix: plain text a user typed into a wired field.
        assertNull(HomeAssistantRef.parse("hub-1|sensor.x|Name"))
        // Too few fields — an older or truncated spec.
        assertNull(HomeAssistantRef.parse("ha:hub-1|sensor.x"))
        // No hub: every one of the three uses has to know which server to talk to.
        assertNull(HomeAssistantRef.parse("ha:|sensor.x|Name"))
        // The other spec's prefix, which must not cross-parse.
        assertNull(HomeAssistantRef.parse("sh:hub-1|LIGHT|rid-9|Lamp"))
    }

    /**
     * The validator asks one question of every hub-scoped field and must not need a
     * `when` over `PickerKind` to pick a parser — which would be a second place for a
     * new picker kind to be forgotten, with no compiler to notice.
     */
    @Test
    fun `the shared hub reader accepts both spellings and nothing else`() {
        val light = SmartHomeRef.format("hub-a", SmartHomeTargetKind.LIGHT, "rid-9", "Lamp")
        val entity = HomeAssistantRef.format("hub-b", "sensor.x", "X")

        assertEquals("hub-a", hubIdOf(light))
        assertEquals("hub-b", hubIdOf(entity))
        assertNull(hubIdOf("something a user typed"))
        assertNull(hubIdOf(""))
    }
}
