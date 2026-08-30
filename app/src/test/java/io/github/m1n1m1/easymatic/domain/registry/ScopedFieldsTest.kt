package io.github.m1n1m1.easymatic.domain.registry

import io.github.m1n1m1.easymatic.core.model.ConfigKey
import io.github.m1n1m1.easymatic.core.model.NodeTypeId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which fields a scope change invalidates, read off the declarations themselves.
 *
 * Driven by `action.ha_service`'s real declaration rather than a fixture, because the thing
 * worth pinning is that the chain **is** what that node declares: hub scopes entity, entity
 * scopes service. A fixture would pass while the node's own `scopedBy` said something else.
 */
class ScopedFieldsTest {

    private val service = NodeTypeId("action.ha_service")

    @Test
    fun `changing the hub clears the entity and, through it, the service`() {
        val cleared = keysScopedBy(service, ConfigKey("hub"))

        assertEquals(setOf(ConfigKey("target"), ConfigKey("service")), cleared)
    }

    /** One link of the same chain, from the middle. */
    @Test
    fun `changing the entity clears the service`() {
        assertEquals(setOf(ConfigKey("service")), keysScopedBy(service, ConfigKey("target")))
    }

    @Test
    fun `a field is never cleared by its own change`() {
        assertTrue(ConfigKey("service") !in keysScopedBy(service, ConfigKey("service")))
        assertTrue(ConfigKey("hub") !in keysScopedBy(service, ConfigKey("hub")))
    }

    /**
     * **The bug this rule exists to stop**, and it was live: entity scopes service *and* service
     * scopes entity, so a symmetric clearing wiped the entity the moment a service was picked —
     * and would have wiped the service the moment an entity was picked. The form could never
     * hold both, and the mutual scope is worth keeping, so clearing only ever runs forwards.
     */
    @Test
    fun `choosing a service leaves the entity above it alone`() {
        assertTrue(keysScopedBy(service, ConfigKey("service")).isEmpty())
    }

    @Test
    fun `an unscoped field clears nothing`() {
        assertTrue(keysScopedBy(service, ConfigKey("data")).isEmpty())
        assertTrue(keysScopedBy(service, ConfigKey("nonsense")).isEmpty())
    }

    /**
     * **A `@Suggested` field is never cleared**, and the asymmetry is the point: its value was
     * *typed*, so it means what somebody meant by it. `brightness` survives the entity changing
     * to another light, where a chosen service does not survive the entity changing to a lock.
     * Deleting somebody's typing is a worse failure than leaving a suggestion that no longer
     * applies.
     */
    @Test
    fun `a typed suggestion survives its scope changing`() {
        val cleared = keysScopedBy(NodeTypeId("value.ha_state"), ConfigKey("entity"))

        assertTrue(ConfigKey("attribute") !in cleared)
        assertTrue(cleared.isEmpty())
    }

    /**
     * The mirror case, and the one that would otherwise ship a silently broken trigger: a
     * trigger id belongs to the entity it was listed for, so `media_player.started_playing`
     * left behind on a door sensor is a subscription Home Assistant will simply refuse.
     */
    @Test
    fun `a chosen trigger is cleared when its entity changes`() {
        assertTrue(ConfigKey("trigger") in keysScopedBy(NodeTypeId("trigger.ha_state"), ConfigKey("entity")))
    }

    @Test
    fun `a node with no scoped fields at all is unaffected`() {
        assertTrue(keysScopedBy(NodeTypeId("action.light_control"), ConfigKey("target")).isEmpty())
        assertTrue(keysScopedBy(NodeTypeId("trigger.ha_event"), ConfigKey("hub")).isEmpty())
    }
}
