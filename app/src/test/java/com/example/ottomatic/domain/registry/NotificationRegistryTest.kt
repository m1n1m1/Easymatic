package com.example.ottomatic.domain.registry

import com.example.ottomatic.domain.model.Direction
import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.NodeKind
import com.example.ottomatic.domain.model.PortKind
import com.example.ottomatic.engine.ForkAction
import com.example.ottomatic.engine.action.NotifyAction
import com.example.ottomatic.engine.action.NotifyCancelAction
import com.example.ottomatic.engine.ai.canRunAsTool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two notification nodes, as the registries see them.
 *
 * The load-bearing one is `action.notify`'s **typeId**: a saved workflow stores that
 * string and an older schema is discarded rather than migrated, so the node that grew
 * buttons has to be the same node it always was. The rest is the shape a
 * `<Feature>RegistryTest` pins — membership, kind, category, ports.
 */
class NotificationRegistryTest {

    @Test
    fun `both are registered as actions under Notifications`() {
        for (typeId in listOf(NOTIFY_TYPE_ID, NOTIFY_CANCEL_TYPE_ID)) {
            val definition = NodeTypeRegistry.byId(typeId)
            assertNotNull("$typeId should be in the registry", definition)
            assertEquals(NodeKind.ACTION, definition!!.kind)
            assertEquals(NodeCategory.NOTIFICATIONS, definition.category)
            assertNotNull("$typeId should have an implementation", ActionRegistry.byId(typeId))
        }
    }

    /**
     * The typeId is permanent. Renaming it would break every macro already using it,
     * with no way back — so the node that gained a second branch keeps the id the
     * fire-and-forget one had.
     */
    @Test
    fun `the poster kept the id it had before it could be answered`() {
        assertEquals("action.notify", NOTIFY_TYPE_ID.value)
    }

    @Test
    fun `the poster is a fork, and the only one a model may call`() {
        val notify = ActionRegistry.byId(NOTIFY_TYPE_ID)
        assertTrue(notify is ForkAction<*>)
        assertTrue("a notification is worth posting even with no graph", canRunAsTool(NOTIFY_TYPE_ID, NodeKind.ACTION))

        // The exclusion it is the exception to: every other fork still stays out,
        // because its inherited `run` does nothing at all.
        val otherForks = ActionRegistry.all()
            .filter { it is ForkAction<*> && it.typeId != NOTIFY_TYPE_ID }
        assertTrue("expected another fork to compare against", otherForks.isNotEmpty())
        assertTrue(otherForks.none { canRunAsTool(it.typeId, NodeKind.ACTION) })
    }

    @Test
    fun `the poster declares both branches and all four data outputs`() {
        val ports = NotifyAction().definition.nodeType.ports
        val execOut = ports.filter { it.kind == PortKind.EXECUTION && it.direction == Direction.OUT }
        assertEquals(2, execOut.size)

        val dataOut = ports.filter { it.kind == PortKind.DATA && it.direction == Direction.OUT }.map { it.name }
        assertEquals(
            listOf(NOTIFY_TAG_OUT, NOTIFY_BUTTON_OUT, NOTIFY_INDEX_OUT, NOTIFY_REPLY_OUT),
            dataOut,
        )
    }

    @Test
    fun `the remover takes a wireable tag and answers with a receipt`() {
        // Wireable because the tag worth removing is usually the one the poster
        // handed out rather than one somebody typed twice.
        val ports = NotifyCancelAction().definition.nodeType.ports
        assertTrue(ports.any { it.kind == PortKind.DATA && it.direction == Direction.IN && it.name.value == "tag" })
        assertTrue(ports.any { it.kind == PortKind.DATA && it.direction == Direction.OUT && it.name.value == "state" })
    }
}
