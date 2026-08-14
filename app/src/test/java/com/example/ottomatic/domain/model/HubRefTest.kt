package com.example.ottomatic.domain.model

import com.example.ottomatic.core.service.SmartHomeTargetKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HubRefTest {

    @Test
    fun `a formatted reference parses back to what it named`() {
        val parsed = HubRef.parse(HubRef.format("hub-1", "Loft broker"))
        assertEquals("hub-1", parsed?.hubId)
        assertEquals("Loft broker", parsed?.name)
    }

    @Test
    fun `a name containing the separator survives the round trip`() {
        assertEquals("Loft | test", HubRef.parse(HubRef.format("hub-1", "Loft | test"))?.name)
    }

    @Test
    fun `an empty name is not a parse failure`() {
        assertEquals("hub-1", HubRef.parse(HubRef.format("hub-1", ""))?.hubId)
    }

    @Test
    fun `anything malformed fails closed`() {
        assertNull(HubRef.parse(""))
        assertNull(HubRef.parse("hub-1|Loft"))
        assertNull(HubRef.parse("hub:"))
        assertNull(HubRef.parse("hub:|Loft"))
        assertNull(HubRef.parse("hub:hub-1"))
    }

    /**
     * The three specs have to stay disjoint, because [hubIdOf] tries them in an
     * arbitrary order — a prefix one parser accepted that another also accepted would
     * make which hub a field names depend on that order.
     */
    @Test
    fun `the three hub-bearing specs do not read each other`() {
        val hub = HubRef.format("hub-1", "Loft broker")
        val homeAssistant = HomeAssistantRef.formatHub("hub-2", "House")
        val light = SmartHomeRef.format("hub-3", SmartHomeTargetKind.LIGHT, "r", "Lamp")

        assertNull(HomeAssistantRef.parse(hub))
        assertNull(SmartHomeRef.parse(hub))
        assertNull(HubRef.parse(homeAssistant))
        assertNull(HubRef.parse(light))

        assertEquals("hub-1", hubIdOf(hub))
        assertEquals("hub-2", hubIdOf(homeAssistant))
        assertEquals("hub-3", hubIdOf(light))
    }
}
