package io.github.m1n1m1.easymatic.domain.registry

import io.github.m1n1m1.easymatic.core.service.SmartHomeTargetKind
import io.github.m1n1m1.easymatic.domain.model.HaEntity
import io.github.m1n1m1.easymatic.domain.model.HaService
import io.github.m1n1m1.easymatic.domain.model.HomeAssistantRef
import io.github.m1n1m1.easymatic.domain.model.SmartHomeResource
import io.github.m1n1m1.easymatic.domain.model.VariableDeclaration
import io.github.m1n1m1.easymatic.domain.model.VariableRef
import io.github.m1n1m1.easymatic.domain.model.WorkflowSummary
import io.github.m1n1m1.easymatic.domain.model.config.PickerKind
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a model may be offered in place of a pinned identifier.
 *
 * Two properties matter and they pull against each other, which is why they are pinned
 * together: what is offered must be **real** — a spec the run path can resolve, never a
 * display name — and what cannot be enumerated must answer **empty** rather than
 * approximately, because an empty enum handed to a provider is an argument the model
 * can never satisfy.
 */
class PickerOptionsTest {

    @After
    fun clearRegistries() {
        SmartHomeHubs.reset()
        HaCatalog.reset()
        MacroDirectory.reset()
        GlobalVariables.reset()
    }

    // ---- what can be offered ----------------------------------------------------

    @Test
    fun `a scene is offered as the spec the run path resolves, not as its name`() {
        SmartHomeHubs.hydrate(
            mapOf("hub-1" to listOf(SmartHomeResource(SmartHomeTargetKind.SCENE, rid = "s1", name = "Dinner"))),
        )
        assertEquals(listOf("sh:hub-1|SCENE|s1|Dinner"), PickerOptions.of(PickerKind.LIGHT_SCENE))
    }

    /** A light target may name a lamp *or* a room, which is what the node accepts. */
    @Test
    fun `a light target offers lights and groups but never scenes`() {
        SmartHomeHubs.hydrate(
            mapOf(
                "hub-1" to listOf(
                    SmartHomeResource(SmartHomeTargetKind.LIGHT, rid = "l1", name = "Desk lamp"),
                    SmartHomeResource(SmartHomeTargetKind.GROUP, rid = "g1", name = "Kitchen"),
                    SmartHomeResource(SmartHomeTargetKind.SCENE, rid = "s1", name = "Dinner"),
                ),
            ),
        )
        val offered = PickerOptions.of(PickerKind.LIGHT_TARGET)
        assertEquals(2, offered.size)
        assertTrue(offered.any { it.contains("Desk lamp") })
        assertTrue(offered.any { it.contains("Kitchen") })
        assertTrue("a scene is not something to dim", offered.none { it.contains("Dinner") })
    }

    @Test
    fun `two hubs both contribute their resources`() {
        SmartHomeHubs.hydrate(
            mapOf(
                "hub-1" to listOf(SmartHomeResource(SmartHomeTargetKind.SCENE, rid = "s1", name = "Dinner")),
                "hub-2" to listOf(SmartHomeResource(SmartHomeTargetKind.SCENE, rid = "s2", name = "Party")),
            ),
        )
        assertEquals(2, PickerOptions.of(PickerKind.LIGHT_SCENE).size)
    }

    @Test
    fun `a macro is offered by id, which is what the field stores`() {
        MacroDirectory.hydrate(listOf(WorkflowSummary(id = "m1", name = "Bed time")))
        assertEquals(listOf("m1"), PickerOptions.of(PickerKind.MACRO))
    }

    /**
     * Globals only: a tool runs from no workflow, so a local declaration would resolve
     * to nothing. The narrowing is forced rather than chosen.
     */
    @Test
    fun `a variable is offered as a global reference`() {
        GlobalVariables.hydrate(listOf(VariableDeclaration(id = "v1", name = "Away")))
        assertEquals(listOf(VariableRef.globalSpec("v1")), PickerOptions.of(PickerKind.VARIABLE))
    }

    // ---- scoping ----------------------------------------------------------------

    @Test
    fun `an entity is offered as a reference carrying its hub`() {
        hydrateTwoHaHubs()
        val offered = PickerOptions.of(PickerKind.HA_ENTITY, scope = listOf(HomeAssistantRef.formatHub("hub-1", "")))
        assertEquals(listOf(HomeAssistantRef.format("hub-1", "light.desk", "Desk")), offered)
    }

    /**
     * The degradation rule in its widening form: nothing has narrowed the question, so
     * the honest answer is every hub's rather than none.
     */
    @Test
    fun `an unpinned hub widens to every hub rather than emptying`() {
        hydrateTwoHaHubs()
        assertEquals(2, PickerOptions.of(PickerKind.HA_ENTITY).size)
    }

    @Test
    fun `a service is offered as domain and service joined, on its hub`() {
        hydrateTwoHaHubs()
        val offered = PickerOptions.of(PickerKind.HA_SERVICE, scope = listOf(HomeAssistantRef.formatHub("hub-2", "")))
        assertEquals(listOf(HomeAssistantRef.format("hub-2", "light.turn_on", "Turn on")), offered)
    }

    /** Any parseable reference narrows it, not only a hub field: an entity carries its hub too. */
    @Test
    fun `a pinned entity narrows the service beside it`() {
        hydrateTwoHaHubs()
        val scope = listOf("", HomeAssistantRef.format("hub-2", "light.desk", "Desk"))
        assertEquals(1, PickerOptions.of(PickerKind.HA_SERVICE, scope).size)
    }

    // ---- what must stay pinned ---------------------------------------------------

    /**
     * Refused on purpose rather than for want of a source. Everything needed to list
     * every model profile is hydrated; handing a model the choice of which model bills
     * the user is not a gap.
     */
    @Test
    fun `the AI model field is never offered even though it could be`() {
        assertEquals(emptyList<String>(), PickerOptions.of(PickerKind.AI_MODEL))
    }

    @Test
    fun `facts about the phone and about credentials are not enumerable here`() {
        val closed = listOf(
            PickerKind.APP,
            PickerKind.APP_FILTER,
            PickerKind.SOUND,
            PickerKind.NFC_TAG,
            PickerKind.MAIL_ACCOUNT,
            PickerKind.GEOFENCE_PLACE,
            PickerKind.HA_TRIGGER,
            PickerKind.HA_HUB,
            PickerKind.MQTT_BROKER,
        )
        closed.forEach { kind ->
            assertEquals("$kind must stay the author's to pin", emptyList<String>(), PickerOptions.of(kind))
        }
    }

    /**
     * **Empty and unasked are the same answer here**, deliberately: both mean "cannot
     * narrow", and the caller's response to either is to leave the field required. A
     * cold start must not hand the model an enum with nothing in it.
     */
    @Test
    fun `every kind answers empty while nothing is hydrated`() {
        PickerKind.entries.forEach { kind ->
            assertEquals("$kind invented options from an empty registry", emptyList<String>(), PickerOptions.of(kind))
        }
    }

    private fun hydrateTwoHaHubs() {
        HaCatalog.hydrate(
            mapOf(
                "hub-1" to (listOf(HaEntity(entityId = "light.desk", name = "Desk")) to emptyList()),
                "hub-2" to (
                    listOf(HaEntity(entityId = "light.hall", name = "Hall")) to
                        listOf(HaService(domain = "light", service = "turn_on", name = "Turn on"))
                    ),
            ),
        )
    }
}
