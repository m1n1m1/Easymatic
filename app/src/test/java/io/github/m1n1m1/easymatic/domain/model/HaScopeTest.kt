package io.github.m1n1m1.easymatic.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reading a scope off a field's siblings.
 *
 * Small, and worth its own file for one reason: the backwards-compatibility of every
 * `action.ha_service` node saved before the `hub` field existed rests entirely on the
 * first-hub-wins rule below. Nothing migrates those macros; this is what makes them open
 * correctly scoped.
 */
class HaScopeTest {

    private fun hub(id: String) = HomeAssistantRef.formatHub(id, "Home Assistant")

    private fun entity(hubId: String, entityId: String) =
        HomeAssistantRef.format(hubId, entityId, entityId)

    @Test
    fun `a hub reference scopes the hub and names no entity`() {
        val scope = haScopeOf(listOf(hub("hub-1")))

        assertEquals("hub-1", scope.hubId)
        assertEquals("", scope.entityId)
    }

    @Test
    fun `an entity reference scopes both`() {
        val scope = haScopeOf(listOf(entity("hub-1", "light.desk")))

        assertEquals("hub-1", scope.hubId)
        assertEquals("light.desk", scope.entityId)
        assertEquals("light", scope.entityDomain)
    }

    /**
     * **The compatibility case, and the whole reason the hub is the first one found.** A node
     * saved before the `hub` field existed has only a service reference — which has always
     * carried its hub inside it — so `scopedBy = ["hub", "service"]` finds nothing in the blank
     * first slot and the right answer in the second. Nothing migrates; it simply works.
     */
    @Test
    fun `the hub is found in a later sibling when the first is blank`() {
        val scope = haScopeOf(listOf("", entity("hub-9", "light.turn_on")))

        assertEquals("hub-9", scope.hubId)
    }

    /** An explicit hub field wins over one carried incidentally inside a later reference. */
    @Test
    fun `an explicit hub takes precedence over an incidental one`() {
        val scope = haScopeOf(listOf(hub("hub-1"), entity("hub-2", "light.desk")))

        assertEquals("hub-1", scope.hubId)
    }

    /**
     * A hub reference carries a blank id by construction, so it can never be mistaken for an
     * entity however the scope list is ordered — which is what lets one list hold both.
     */
    @Test
    fun `a hub reference is never mistaken for an entity`() {
        assertEquals("", haScopeOf(listOf(hub("hub-1"), hub("hub-2"))).entityId)
    }

    /** The last entity wins, so an explicit entity field beats anything earlier. */
    @Test
    fun `the last entity named is the one that scopes`() {
        val scope = haScopeOf(listOf(entity("hub-1", "light.a"), entity("hub-1", "lock.b")))

        assertEquals("lock.b", scope.entityId)
        assertEquals("lock", scope.entityDomain)
    }

    /**
     * The rule the whole change depends on: blank means *do not narrow*, never *narrow to
     * nothing*. A form that silently empties its own choosers is worse than one never narrowed.
     */
    @Test
    fun `nothing usable narrows nothing`() {
        assertTrue(haScopeOf(emptyList()).isEmpty)
        assertTrue(haScopeOf(listOf("", "  ")).isEmpty)
        // Plain text somebody typed into a wired field, not a reference at all.
        assertTrue(haScopeOf(listOf("light.desk")).isEmpty)
        assertEquals("", haScopeOf(emptyList()).entityDomain)
    }
}
