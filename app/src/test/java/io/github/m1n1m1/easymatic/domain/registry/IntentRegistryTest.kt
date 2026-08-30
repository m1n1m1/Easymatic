package io.github.m1n1m1.easymatic.domain.registry

import io.github.m1n1m1.easymatic.core.model.NodeTypeId
import io.github.m1n1m1.easymatic.core.model.PortName
import io.github.m1n1m1.easymatic.domain.model.Direction
import io.github.m1n1m1.easymatic.domain.model.NodeCategory
import io.github.m1n1m1.easymatic.domain.model.NodeIcon
import io.github.m1n1m1.easymatic.domain.model.NodeKind
import io.github.m1n1m1.easymatic.domain.model.PortKind
import io.github.m1n1m1.easymatic.domain.model.config.PickerKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two intent nodes' wiring, and the three declarations that would degrade in silence.
 *
 * The load-bearing one is the **permission asymmetry**: Send Intent must declare the overlay
 * grant and Broadcast Intent must not, and neither direction is a compile error. That asymmetry
 * is the whole reason these are two nodes rather than one with a "Send as" field, so a change
 * that erased it would quietly erase the design too.
 *
 * The second is that they carry **different icons**. Nothing fails to build when two nodes take
 * the same glyph, and these two sit next to each other in the palette and differ in nothing else
 * a card shows.
 *
 * The third is the **new category**, which exists so the general node lands beside the two
 * special cases it subsumes rather than in a group nobody browsing Launch App would open.
 */
class IntentRegistryTest {

    private val send = NodeTypeId("action.send_intent")
    private val broadcast = NodeTypeId("action.broadcast_intent")
    private val both = listOf(send, broadcast)

    @Test
    fun `both are registered`() {
        both.forEach {
            assertNotNull("$it must reach NodeTypeRegistry", NodeTypeRegistry.byId(it))
            assertNotNull("$it must be an executable action", ActionRegistry.byId(it))
        }
    }

    @Test
    fun `both are actions filed under Apps and Intents`() {
        both.forEach {
            val definition = NodeTypeRegistry.byId(it)!!
            assertEquals(NodeKind.ACTION, definition.kind)
            assertEquals(
                "$it belongs beside Launch App and Open URL, not under Network",
                NodeCategory.APPS,
                definition.category,
            )
        }
    }

    /**
     * The generalisation has to share a palette group with the two special cases it subsumes:
     * somebody who has outgrown Launch App looks next to Launch App.
     */
    @Test
    fun `the two nodes it generalises moved with it`() {
        listOf("action.launch_app", "action.open_url").forEach {
            assertEquals(
                "$it is about handing something to another app, which is what this group means",
                NodeCategory.APPS,
                NodeTypeRegistry.byId(NodeTypeId(it))!!.category,
            )
        }
    }

    @Test
    fun `only the activity node declares the overlay grant`() {
        assertTrue(
            "Send Intent starts an Activity, so a background run silently fails without it",
            NodeTypeRegistry.byId(send)!!.permissionRequirements.isNotEmpty(),
        )
        assertEquals(
            "A broadcast is under no background-start rule; a grant here would badge the node for working",
            emptyList<Any>(),
            NodeTypeRegistry.byId(broadcast)!!.permissionRequirements,
        )
    }

    @Test
    fun `the two carry different icons`() {
        assertEquals(NodeIcon.INTENT, NodeTypeRegistry.byId(send)!!.icon)
        assertEquals(NodeIcon.BROADCAST, NodeTypeRegistry.byId(broadcast)!!.icon)
    }

    /**
     * Six `@Wired` config properties, so six DATA inputs — which is the point of the node: an
     * action worked out by a script, or a `content://` a previous node wrote, has to be able to
     * reach it. No DATA output, because there is no reply to be had from `startActivity`.
     */
    @Test
    fun `both take every field as a data input and answer with none`() {
        both.forEach {
            val ports = NodeTypeRegistry.byId(it)!!.ports
            assertEquals(
                listOf("action", "packageName", "dataUri", "mimeType", "category", "extras").sorted(),
                ports.filter { p -> p.kind == PortKind.DATA && p.direction == Direction.IN }
                    .map { p -> p.name.value }
                    .sorted(),
            )
            assertTrue(
                "$it has nothing to say back",
                ports.none { p -> p.kind == PortKind.DATA && p.direction == Direction.OUT },
            )
            assertEquals(
                listOf(PortName("out")),
                ports.filter { p -> p.kind == PortKind.EXECUTION && p.direction == Direction.OUT }
                    .map { p -> p.name },
            )
        }
    }

    /**
     * The app is chosen rather than typed, and from the *filter* kind rather than the launchable
     * one: an app that only registers a receiver has no launcher activity, and blank — "let
     * Android resolve it" — is a valid answer here.
     */
    @Test
    fun `the app field is a picker over every installed app`() {
        both.forEach {
            val field = ConfigSchemaRegistry.byId(it)!!.fields.single { f -> f.key.value == "packageName" }
            assertEquals(ConfigFieldType.PICKER(PickerKind.APP_FILTER), field.type)
        }
    }

    /** A typeId is permanent once a macro has used it, so a rename has to be a deliberate act. */
    @Test
    fun `the ids are the ones macros will persist`() {
        assertNull(NodeTypeRegistry.byId(NodeTypeId("action.intent")))
        assertNull(NodeTypeRegistry.byId(NodeTypeId("action.start_activity")))
    }
}
