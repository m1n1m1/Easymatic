package com.example.ottomatic.engine.validation

import com.example.ottomatic.core.capabilities.DeviceCapability
import com.example.ottomatic.core.model.NodeId
import com.example.ottomatic.core.model.NodeTypeId
import com.example.ottomatic.domain.model.Workflow
import com.example.ottomatic.domain.model.WorkflowNode
import com.example.ottomatic.domain.registry.DeviceCapabilities
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the Problems panel says about a node whose hardware this phone does not have.
 *
 * The second axis beside a permission, and the reason it is one: a grant is something
 * the user can go and give, and this is not. `trigger.fingerprint_gesture` is the node
 * that forced it — accessibility access can be granted and most phones' readers still
 * report no swipes, so the commonest failure here is one no permission check can see,
 * and it looks exactly like a macro waiting for its trigger.
 *
 * Its own class rather than more cases in [GraphValidatorTest], which is at detekt's
 * size limit, and on [PluginReadinessValidationTest]'s reasoning: the load-bearing
 * assertions are about what the warning **does not** do — it blocks nothing, and it says
 * nothing at all while the answer is still unknown.
 */
class CapabilityValidationTest {

    /**
     * The second axis. `trigger.fingerprint_gesture` is the node it exists for: the
     * accessibility grant can be given, and on most phones the reader still reports no
     * swipes, so the commonest failure is one no permission check can see.
     *
     * Blocks nothing, and for a reason sharper than the prerequisite family's. There is
     * no Settings page here and nothing the user can do — but the macro is not broken,
     * it is *portable*, and quarantining the node would take out work on the phone that
     * can run it.
     */
    @Test
    fun `hardware this phone does not have warns and blocks nothing`() {
        DeviceCapabilities.hydrate(emptySet())
        try {
            val node = WorkflowNode(
                NodeId("fp"),
                NodeTypeId("trigger.fingerprint_gesture"),
                "Fingerprint",
                0f,
                0f,
            )

            val validation = GraphValidator(Workflow(nodes = listOf(node))).validate()

            val warning = validation.warnings.single { "this phone does not have" in it.message }
            assertTrue(warning.message, node.id in warning.nodes)
            // Names the thing in words, not a constant: "FINGERPRINT_GESTURES" sends
            // nobody anywhere, and there is nowhere to send them anyway.
            assertTrue(warning.message, "reports swipes" in warning.message)
            assertFalse("no enum constants in a user-facing line", "FINGERPRINT_" in warning.message)
            assertTrue(validation.blockedNodes.isEmpty())
            assertTrue(validation.isRunnable)
        } finally {
            DeviceCapabilities.reset()
        }
    }

    @Test
    fun `hardware this phone has says nothing`() {
        DeviceCapabilities.hydrate(setOf(DeviceCapability.FINGERPRINT_GESTURES.name))
        try {
            val node = WorkflowNode(
                NodeId("fp"),
                NodeTypeId("trigger.fingerprint_gesture"),
                "Fingerprint",
                0f,
                0f,
            )

            val validation = GraphValidator(Workflow(nodes = listOf(node))).validate()

            assertFalse(
                validation.warnings.toString(),
                validation.warnings.any { "this phone does not have" in it.message },
            )
        } finally {
            DeviceCapabilities.reset()
        }
    }

    /**
     * [nothing is reported while the grants are unknown]'s sibling, and it matters as
     * much: the executor validates a disk snapshot on boot, long before anything has
     * been able to ask the accessibility service what this reader can do.
     */
    @Test
    fun `nothing is reported while the hardware is unknown`() {
        DeviceCapabilities.reset()
        val node = WorkflowNode(
            NodeId("fp"),
            NodeTypeId("trigger.fingerprint_gesture"),
            "Fingerprint",
            0f,
            0f,
        )

        val validation = GraphValidator(Workflow(nodes = listOf(node))).validate()

        assertFalse(
            validation.warnings.toString(),
            validation.warnings.any { "this phone does not have" in it.message },
        )
    }
}
