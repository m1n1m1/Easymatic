package io.github.m1n1m1.easymatic.domain.registry

import io.github.m1n1m1.easymatic.domain.model.HaEntity
import io.github.m1n1m1.easymatic.domain.model.HomeAssistantRef
import io.github.m1n1m1.easymatic.domain.model.config.SuggestionSource
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a `@Suggested` field offers.
 *
 * Every case here is really the same case stated four ways: **empty means "I cannot narrow
 * this", never "there is nothing"**. That distinction has no visible difference in a passing
 * form and is the whole failure mode when it is wrong — a chooser that silently empties itself
 * looks exactly like a hub with nothing on it.
 */
class SuggestionsTest {

    @After
    fun tearDown() {
        HaCatalog.reset()
    }

    private fun entity(id: String, attributes: List<String> = emptyList()) =
        HaEntity(entityId = id, name = id, domain = id.substringBefore('.'), attributes = attributes)

    private fun hydrate(vararg entities: HaEntity) {
        HaCatalog.hydrate(mapOf("hub-1" to (entities.toList() to emptyList())))
    }

    private fun ref(entityId: String) = HomeAssistantRef.format("hub-1", entityId, entityId)

    @Test
    fun `an entity's attributes are offered`() {
        hydrate(entity("light.desk", attributes = listOf("brightness", "friendly_name")))

        assertEquals(
            listOf("brightness", "friendly_name"),
            Suggestions.of(SuggestionSource.HA_ENTITY_ATTRIBUTE, listOf(ref("light.desk"))),
        )
    }

    /**
     * The `unavailable` case. The entity is perfectly well known — it simply published almost
     * nothing at Refresh — and the field must behave as an ordinary text box rather than as a
     * chooser with nothing in it.
     */
    @Test
    fun `an entity that published nothing offers nothing`() {
        hydrate(entity("sensor.temperature"))

        assertTrue(Suggestions.of(SuggestionSource.HA_ENTITY_ATTRIBUTE, listOf(ref("sensor.temperature"))).isEmpty())
    }

    /**
     * A validator running in a process that has never listed hubs would otherwise narrow every
     * chooser in every macro to nothing. Empty and unasked are different states —
     * [SmartHomeHubs]' rule, and it matters more here because this one narrows a form rather
     * than raising a warning.
     */
    @Test
    fun `an unhydrated catalogue narrows nothing`() {
        HaCatalog.reset()

        assertFalse(HaCatalog.isHydrated)
        assertTrue(Suggestions.of(SuggestionSource.HA_ENTITY_ATTRIBUTE, listOf(ref("light.desk"))).isEmpty())
    }

    @Test
    fun `a blank or unparseable scope narrows nothing`() {
        hydrate(entity("light.desk", attributes = listOf("brightness")))

        assertTrue(Suggestions.of(SuggestionSource.HA_ENTITY_ATTRIBUTE, emptyList()).isEmpty())
        assertTrue(Suggestions.of(SuggestionSource.HA_ENTITY_ATTRIBUTE, listOf("")).isEmpty())
        // Plain text in a wired field, not a reference.
        assertTrue(Suggestions.of(SuggestionSource.HA_ENTITY_ATTRIBUTE, listOf("light.desk")).isEmpty())
    }

    @Test
    fun `an entity the catalogue has never seen offers nothing`() {
        hydrate(entity("light.desk", attributes = listOf("brightness")))

        assertTrue(Suggestions.of(SuggestionSource.HA_ENTITY_ATTRIBUTE, listOf(ref("light.missing"))).isEmpty())
    }

    /**
     * A mailbox list is an authenticated IMAP round trip, so it cannot be answered from a
     * registry — its widget asks the ViewModel instead. Keeping it a member of the same enum is
     * what lets a node author write one annotation without knowing which kind theirs is.
     */
    @Test
    fun `a remote source answers nothing here and says so`() {
        assertFalse(Suggestions.isLocal(SuggestionSource.MAIL_FOLDER))
        assertTrue(Suggestions.isLocal(SuggestionSource.HA_ENTITY_ATTRIBUTE))
        assertTrue(Suggestions.of(SuggestionSource.MAIL_FOLDER, listOf("account-1")).isEmpty())
    }
}
