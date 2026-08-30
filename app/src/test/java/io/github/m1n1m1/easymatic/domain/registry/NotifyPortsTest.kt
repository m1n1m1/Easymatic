package io.github.m1n1m1.easymatic.domain.registry

import io.github.m1n1m1.easymatic.core.model.ConfigKey
import io.github.m1n1m1.easymatic.core.model.NodeId
import io.github.m1n1m1.easymatic.domain.model.Direction
import io.github.m1n1m1.easymatic.domain.model.ExecPorts
import io.github.m1n1m1.easymatic.domain.model.PortKind
import io.github.m1n1m1.easymatic.domain.model.Workflow
import io.github.m1n1m1.easymatic.domain.model.WorkflowNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When `action.notify` grows its second branch.
 *
 * `DialogPortsTest`'s rule applied to a whole branch rather than one route: a port
 * exists once it has been asked for. The load-bearing assertion is the *negative* one
 * — a plain notification must still look exactly like the fire-and-forget node it was,
 * because every macro that already uses it has one, and a `When answered` handle that
 * can never fire is an invitation to wire something that will never run.
 */
class NotifyPortsTest {

    // region When the branch is drawn

    /**
     * Which is also why a tap is not enough on its own: every notification is
     * tappable, so if it were, *this* case would sprout a branch nobody asked for. A
     * tap is reported once there is a button to report it alongside — see
     * `NotifyActionTest`.
     */
    @Test
    fun `a plain notification has no answered branch and no answer ports`() {
        val ports = portNames(emptyMap())
        assertFalse(ExecPorts.RESUMED in ports)
        assertFalse(NOTIFY_BUTTON_OUT in ports)
        assertFalse(NOTIFY_INDEX_OUT in ports)
        assertFalse(NOTIFY_REPLY_OUT in ports)
    }

    @Test
    fun `buttons reveal it`() {
        assertTrue(ExecPorts.RESUMED in portNames(mapOf(NOTIFY_BUTTONS_KEY to "Yes\nLater")))
    }

    @Test
    fun `a reply field reveals it on its own`() {
        assertTrue(ExecPorts.RESUMED in portNames(mapOf(NOTIFY_REPLY_KEY to "true")))
    }

    @Test
    fun `a blank buttons field reads as no buttons`() {
        // Which is what a node straight out of the palette holds, and what a
        // `@Wired` buttons port that arrived empty leaves behind.
        assertFalse(ExecPorts.RESUMED in portNames(mapOf(NOTIFY_BUTTONS_KEY to "   ")))
    }

    @Test
    fun `a switched-off flag reads as off rather than as configured`() {
        assertFalse(ExecPorts.RESUMED in portNames(mapOf(NOTIFY_REPLY_KEY to "false")))
    }

    // endregion

    // region What is never hidden

    @Test
    fun `the immediate branch and the tag survive an unanswerable notification`() {
        val ports = portNames(emptyMap())
        assertTrue(ExecPorts.OUT in ports)
        assertTrue(NOTIFY_TAG_OUT in ports)
    }

    @Test
    fun `the branch stays declared even while its handle is hidden`() {
        // The executor pulses `resumed` by name, so hiding the handle must never be
        // able to remove the port the declaration promises — `DialogPortsTest` pins
        // the same thing about `timed_out`.
        val declared = NodeTypeRegistry.byId(NOTIFY_TYPE_ID)!!.ports
        assertTrue(declared.any { it.kind == PortKind.EXECUTION && it.name == ExecPorts.RESUMED })
    }

    @Test
    fun `the two branches are labelled for a person rather than for a clock`() {
        val ports = NodeTypeRegistry.byId(NOTIFY_TYPE_ID)!!.ports.associateBy { it.name }
        assertEquals(ExecPorts.CONTINUE_LABEL, ports[ExecPorts.OUT]?.label)
        // Not RESUMED_LABEL: "When the time comes" promises a moment that will
        // arrive, and nobody may ever touch a notification.
        assertEquals(ExecPorts.ANSWERED_LABEL, ports[ExecPorts.RESUMED]?.label)
    }

    /**
     * The input and the output must not share a name.
     *
     * They are allowed to by the port rules — different directions — but the
     * generated string key is `port_<typeId>_<portName>` with no direction in it, so
     * two same-named ports collide there and `NodeStringsSyncTest` fails a long way
     * from the node that caused it.
     */
    @Test
    fun `the tag it posted under is named apart from the tag it was given`() {
        val ports = NodeTypeRegistry.byId(NOTIFY_TYPE_ID)!!.ports
        val out = ports.single { it.direction == Direction.OUT && it.name == NOTIFY_TAG_OUT }
        assertTrue(ports.none { it.direction == Direction.IN && it.name == out.name })
    }

    // endregion

    private fun portNames(config: Map<ConfigKey, String>) = WorkflowNode(
        NodeId("n"), NOTIFY_TYPE_ID, "Notify", 0f, 0f, config = config,
    ).let { placed ->
        effectivePorts(NodeTypeRegistry.byId(NOTIFY_TYPE_ID)!!, Workflow(nodes = listOf(placed)), placed)
            .map { it.name }
    }
}
