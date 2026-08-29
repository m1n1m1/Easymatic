package com.example.ottomatic.domain.registry

import com.example.ottomatic.core.model.NodeTypeId
import com.example.ottomatic.core.model.PortName
import com.example.ottomatic.core.permissions.Permissions
import com.example.ottomatic.core.permissions.PrerequisiteType
import com.example.ottomatic.domain.model.Direction
import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.NodeKind
import com.example.ottomatic.domain.model.PortKind
import com.example.ottomatic.domain.model.items.CallEvent
import com.example.ottomatic.domain.model.schema.ItemSchema
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The four call nodes as a family.
 *
 * What is pinned here is that they **declare both grants**, which is the whole of how a
 * half-granted call integration explains itself. A call reaches this app down two roads —
 * telephony for the cellular radio, notifications for every other calling app — and
 * somebody who grants one and not the other gets a node that half works. Declared, the
 * Problems panel and the node's own card say which half is missing; undeclared, a Teams
 * macro simply never fires and nothing anywhere says why. That failure is exactly the one
 * `trigger.sms` and the old undeclared `READ_PHONE_STATE` on this very node already had.
 */
class CallRegistryTest {

    private val callNodes = listOf(
        NodeTypeId("trigger.call_state"),
        NodeTypeId("trigger.call_ended"),
        NodeTypeId("value.call_active"),
        NodeTypeId("value.current_call"),
    )

    @Test
    fun `every call node is registered and carries the call icon`() {
        for (typeId in callNodes) {
            val definition = NodeTypeRegistry.byId(typeId)
            assertNotNull("$typeId is missing from NodeTypeRegistry", definition)
            assertEquals("$typeId has the wrong icon", "CALL", definition!!.icon.name)
        }
    }

    @Test
    fun `the two triggers are phone triggers emitting a call event`() {
        for (typeId in listOf(NodeTypeId("trigger.call_state"), NodeTypeId("trigger.call_ended"))) {
            val definition = NodeTypeRegistry.byId(typeId)!!
            assertEquals(NodeKind.TRIGGER, definition.kind)
            assertEquals(NodeCategory.PHONE_MEDIA, definition.category)

            val dataOut = definition.ports.filter { it.kind == PortKind.DATA && it.direction == Direction.OUT }
            assertEquals("$typeId should publish one struct", 1, dataOut.size)
            assertEquals(PortName("call"), dataOut.single().name)
        }
    }

    /**
     * The struct is shared on purpose: "when a call ends, turn the music back on" is one
     * macro whether the call was on the SIM or in Teams, and it stays one only while both
     * triggers hand downstream the same shape.
     */
    @Test
    fun `both triggers publish the same struct as the value node`() {
        val schemas = (callNodes - NodeTypeId("value.call_active")).map { typeId ->
            NodeTypeRegistry.byId(typeId)!!
                .ports
                .single { it.kind == PortKind.DATA && it.direction == Direction.OUT }
                .schema
        }

        assertEquals("all three should carry one schema", 1, schemas.distinct().size)
        assertEquals(CallEvent::class, (schemas.first() as? ItemSchema.Object)?.kClass)
    }

    @Test
    fun `the two values are pure leaves on the device shelf`() {
        for (typeId in listOf(NodeTypeId("value.call_active"), NodeTypeId("value.current_call"))) {
            val definition = NodeTypeRegistry.byId(typeId)!!
            assertEquals(NodeKind.VALUE, definition.kind)
            assertEquals(NodeCategory.VALUE_DEVICE, definition.category)
            assertTrue(
                "$typeId is a value and cannot have execution ports",
                definition.ports.none { it.kind == PortKind.EXECUTION },
            )
        }
    }

    /**
     * The load-bearing one. Both grants on all four, so neither half of the integration
     * can go missing silently.
     */
    @Test
    fun `every call node declares both the telephony and the notification grant`() {
        for (typeId in callNodes) {
            val requirements = NodeTypeRegistry.byId(typeId)!!.permissionRequirements

            assertTrue(
                "$typeId does not declare READ_PHONE_STATE, so a denied grant would " +
                    "leave cellular calls silently unseen",
                requirements.any {
                    it.type == PrerequisiteType.RUNTIME &&
                        it.manifestPermission == Permissions.READ_PHONE_STATE.manifest
                },
            )
            assertTrue(
                "$typeId does not declare notification access, so calls in Teams, " +
                    "WhatsApp and Discord would silently never be seen",
                requirements.any { it.type == PrerequisiteType.NOTIFICATION_LISTENER },
            )
        }
    }
}
