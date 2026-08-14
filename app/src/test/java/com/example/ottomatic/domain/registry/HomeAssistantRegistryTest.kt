package com.example.ottomatic.domain.registry

import com.example.ottomatic.core.model.NodeTypeId
import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.NodeKind
import com.example.ottomatic.domain.model.PortKind
import com.example.ottomatic.domain.model.config.PickerKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The four Home Assistant nodes as *declarations*, on `SmartHomeRegistryTest`'s shape.
 *
 * These assert the things that are decided once in a node file and are then invisible —
 * which picker a field uses, which category a node lands in, what its ports are — and
 * which no behaviour test would notice going wrong, because a node with the wrong picker
 * still runs perfectly against whatever it was handed.
 */
class HomeAssistantRegistryTest {

    private fun definition(typeId: String) = NodeTypeRegistry.byId(NodeTypeId(typeId))

    private fun fields(typeId: String) = ConfigSchemaRegistry.byId(NodeTypeId(typeId))?.fields.orEmpty()

    private fun pickerOf(typeId: String, key: String) =
        (fields(typeId).firstOrNull { it.key.value == key }?.type as? ConfigFieldType.PICKER)?.kind

    @Test
    fun `all four nodes are registered under the right kind`() {
        assertEquals(NodeKind.TRIGGER, definition("trigger.ha_state")?.kind)
        assertEquals(NodeKind.TRIGGER, definition("trigger.ha_event")?.kind)
        assertEquals(NodeKind.VALUE, definition("value.ha_state")?.kind)
        assertEquals(NodeKind.ACTION, definition("action.ha_service")?.kind)
    }

    /**
     * The triggers and the value node land in the two categories added for them rather
     * than in the action-side `SMART_HOME`, which would put a trigger in a group the
     * palette draws under Actions.
     */
    @Test
    fun `each node is in the category for its own side of the palette`() {
        assertEquals(NodeCategory.SMART_HOME_EVENTS, definition("trigger.ha_state")?.category)
        assertEquals(NodeCategory.SMART_HOME_EVENTS, definition("trigger.ha_event")?.category)
        assertEquals(NodeCategory.VALUE_SMART_HOME, definition("value.ha_state")?.category)
        assertEquals(NodeCategory.SMART_HOME, definition("action.ha_service")?.category)
    }

    /**
     * Every identifier that comes out of a hub's snapshot is chosen, never typed. A
     * mistyped entity id names nothing and the node simply looks broken.
     */
    @Test
    fun `entities services and hubs are chosen rather than typed`() {
        assertEquals(PickerKind.HA_ENTITY, pickerOf("trigger.ha_state", "entity"))
        assertEquals(PickerKind.HA_ENTITY, pickerOf("value.ha_state", "entity"))
        assertEquals(PickerKind.HA_ENTITY, pickerOf("action.ha_service", "target"))
        assertEquals(PickerKind.HA_SERVICE, pickerOf("action.ha_service", "service"))
        assertEquals(PickerKind.HA_HUB, pickerOf("trigger.ha_event", "hub"))
    }

    /**
     * The one Home Assistant field that is **not** a picker, and the exception is
     * forced rather than chosen: Home Assistant publishes no way to list event types, so
     * a chooser could only offer types already seen — which excludes the one the user is
     * setting the node up for.
     */
    @Test
    fun `the event type is typed, because nothing can list them`() {
        assertNull(pickerOf("trigger.ha_event", "eventType"))
        assertNotNull(fields("trigger.ha_event").firstOrNull { it.key.value == "eventType" })
    }

    /**
     * A value node has **no exec ports and no data inputs** — the purity contract.
     * `NodeDeclarationContractTest` pins this across every value node; it is repeated
     * here because `value.ha_state` is the one whose legality rests on an argument, and
     * a reader coming to that argument should find the shape asserted beside it.
     */
    @Test
    fun `the value node is pure, whatever it reads`() {
        val ports = definition("value.ha_state")!!.ports

        assertTrue(ports.none { it.kind == PortKind.EXECUTION })
        assertTrue(ports.none { it.kind == PortKind.DATA && it.direction.name == "IN" })
        assertEquals(1, ports.count { it.kind == PortKind.DATA })
    }

    /** No node here declares a permission: `INTERNET` is install-time and has no entry. */
    @Test
    fun `no Home Assistant node declares a permission`() {
        for (typeId in listOf("trigger.ha_state", "trigger.ha_event", "value.ha_state", "action.ha_service")) {
            assertTrue(typeId, definition(typeId)!!.permissionRequirements.isEmpty())
        }
    }

    /**
     * The vendor's name lives in the description, which `NodeSuggestions` searches — so
     * somebody typing "home assistant" into the palette finds all four, even though not
     * one typeId contains it.
     */
    @Test
    fun `every description names the vendor so palette search finds it`() {
        for (typeId in listOf("trigger.ha_state", "trigger.ha_event", "value.ha_state", "action.ha_service")) {
            assertTrue(typeId, definition(typeId)!!.description.contains("Home Assistant"))
        }
    }

    /**
     * The typeIds say `ha`, never `homeassistant` spelled out and never a vendor's
     * product name — and they can never change: a workflow persists the string, and an
     * unknown one is a discarded schema rather than a migration.
     */
    @Test
    fun `the typeIds are the ones saved workflows carry`() {
        for (typeId in listOf("trigger.ha_state", "trigger.ha_event", "value.ha_state", "action.ha_service")) {
            assertNotNull(typeId, definition(typeId))
        }
    }
}
