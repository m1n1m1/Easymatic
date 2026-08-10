package com.example.ottomatic.domain.model

import com.example.ottomatic.core.service.SmartHomeTargetKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The target spec, pinned in both directions.
 *
 * Every one of these exists because the alternative to failing closed is acting on
 * *some* light: a half-parsed reference that keeps its hub id and loses its rid, or
 * keeps its rid and loses its kind, would address something — and turning on the
 * wrong lamp is worse than reporting that there was nothing to turn on.
 */
class SmartHomeRefTest {

    @Test
    fun `a formatted reference parses back to what it named`() {
        val spec = SmartHomeRef.format("hub-1", SmartHomeTargetKind.LIGHT, "rid-9", "Desk lamp")
        val parsed = SmartHomeRef.parse(spec)!!

        assertEquals("hub-1", parsed.hubId)
        assertEquals(SmartHomeTargetKind.LIGHT, parsed.kind)
        assertEquals("rid-9", parsed.rid)
        assertEquals("Desk lamp", parsed.name)
    }

    /**
     * People name scenes things like "Dinner | warm", and the name is last and
     * unsplit precisely so that it survives. The three fields before it cannot
     * contain a separator — a UUID, an enum constant and a bridge rid are hex and
     * hyphens — which is what makes the limit safe.
     */
    @Test
    fun `a name containing the separator survives the round trip`() {
        val spec = SmartHomeRef.format("hub-1", SmartHomeTargetKind.SCENE, "rid-2", "Dinner | warm")

        assertEquals("Dinner | warm", SmartHomeRef.parse(spec)!!.name)
    }

    @Test
    fun `an empty name is not a parse failure`() {
        val spec = SmartHomeRef.format("hub-1", SmartHomeTargetKind.GROUP, "rid-3", "")

        assertEquals("", SmartHomeRef.parse(spec)!!.name)
        assertEquals(SmartHomeTargetKind.GROUP, SmartHomeRef.parse(spec)!!.kind)
    }

    @Test
    fun `anything malformed parses to nothing at all`() {
        assertNull(SmartHomeRef.parse(""))
        // No prefix: plain text a user typed into a wired field.
        assertNull(SmartHomeRef.parse("hub-1|LIGHT|rid-9|Lamp"))
        // Too few fields — an older or truncated spec.
        assertNull(SmartHomeRef.parse("sh:hub-1|LIGHT|rid-9"))
        // A kind this build does not know, which is what a downgrade looks like.
        assertNull(SmartHomeRef.parse("sh:hub-1|CURTAIN|rid-9|Blind"))
        assertNull(SmartHomeRef.parse("sh:|LIGHT|rid-9|Lamp"))
        assertNull(SmartHomeRef.parse("sh:hub-1|LIGHT||Lamp"))
    }
}
