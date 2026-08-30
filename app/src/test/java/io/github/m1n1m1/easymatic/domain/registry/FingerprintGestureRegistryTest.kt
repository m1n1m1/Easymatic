package io.github.m1n1m1.easymatic.domain.registry

import io.github.m1n1m1.easymatic.core.capabilities.DeviceCapability
import io.github.m1n1m1.easymatic.core.model.ConfigKey
import io.github.m1n1m1.easymatic.core.model.PortName
import io.github.m1n1m1.easymatic.core.permissions.PrerequisiteType
import io.github.m1n1m1.easymatic.data.accessibility.EasymaticAccessibilityService
import io.github.m1n1m1.easymatic.domain.model.Direction
import io.github.m1n1m1.easymatic.domain.model.NodeCategory
import io.github.m1n1m1.easymatic.domain.model.PortKind
import io.github.m1n1m1.easymatic.engine.trigger.FingerprintGestureTrigger
import io.github.m1n1m1.easymatic.engine.trigger.gesture.FingerprintGesture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Registry and schema contract for `trigger.fingerprint_gesture`. */
class FingerprintGestureRegistryTest {

    @Test
    fun `the trigger is registered under Sensors and Gestures`() {
        assertNotNull(TriggerRegistry.byId(FingerprintGestureTrigger.TYPE_ID))
        val definition = NodeTypeRegistry.byId(FingerprintGestureTrigger.TYPE_ID)
        assertNotNull(definition)
        // Grouped by subject rather than by mechanism: it is armed by the accessibility
        // service like `trigger.volume_button`, but a swipe is a gesture and that is
        // where somebody goes looking for one.
        assertEquals(NodeCategory.SENSORS, definition!!.category)
    }

    @Test
    fun `it exposes one exec out and a SystemState data out`() {
        val definition = NodeTypeRegistry.byId(FingerprintGestureTrigger.TYPE_ID)!!
        val exec = definition.ports.filter { it.kind == PortKind.EXECUTION }
        val dataOut = definition.ports.filter { it.kind == PortKind.DATA && it.direction == Direction.OUT }
        assertEquals(1, exec.size)
        assertEquals(1, dataOut.size)
        assertEquals(PortName("state"), dataOut.single().name)
        assertTrue(definition.ports.none { it.kind == PortKind.DATA && it.direction == Direction.IN })
    }

    @Test
    fun `it declares the accessibility prerequisite with a rationale`() {
        val requirement = NodeTypeRegistry.byId(FingerprintGestureTrigger.TYPE_ID)!!
            .permissionRequirements
            .single()

        assertEquals(PrerequisiteType.ACCESSIBILITY_SERVICE, requirement.type)
        // No android.permission.* name exists for this; it is a Settings toggle.
        assertNull(requirement.manifestPermission)
        // Without a rationale key the editor renders no card at all, and the user
        // would be sent to a settings page with no idea what to switch on.
        assertTrue(requirement.rationaleKey.isNotBlank())
    }

    @Test
    fun `it declares the hardware capability as well as the grant`() {
        // The two are separate axes on purpose, and this node is the reason the second
        // exists: accessibility access can be granted, and on most phones the reader
        // still reports no swipes. Declaring only the prerequisite would leave the
        // commonest failure completely silent.
        assertEquals(
            listOf(DeviceCapability.FINGERPRINT_GESTURES),
            NodeTypeRegistry.byId(FingerprintGestureTrigger.TYPE_ID)!!.capabilities,
        )
    }

    @Test
    fun `the capability stays off the Permissions screen`() {
        // A capability could never go green there. The whole reason it is not a
        // PrerequisiteType is that the Permissions screen answers "what can you fix?",
        // so a missing sensor must not appear on it — see AndroidPermissionChecker's
        // NFC branch for the same call made from the other side.
        val keys = PermissionCatalogue.entries().map { it.requirement.key }
        assertTrue(keys.none { it == DeviceCapability.FINGERPRINT_GESTURES.name })
    }

    @Test
    fun `the gesture filter defaults to any`() {
        // A blank default is what puts the "Any" option in front of the four, and is
        // how the node says "start on any swipe" without a fifth enum member.
        val schema = ConfigSchemaRegistry.byId(FingerprintGestureTrigger.TYPE_ID)!!
        val gesture = schema.fields.single { it.key == ConfigKey("gesture") }
        assertTrue(gesture.defaultValue.isBlank())
        val options = (gesture.type as ConfigFieldType.ENUM).options
        assertEquals(
            FingerprintGesture.entries.map { it.name },
            options.map { it.value }.filter { it.isNotBlank() },
        )
        // The blank one is what the form labels "Any"; without it the node could not
        // say "no filter" at all.
        assertTrue(options.any { it.value.isBlank() })
    }

    @Test
    fun `the payload contract matches the service that emits it` () {
        // `data` may not import `engine`, so both sides name these strings themselves.
        // This is what stops the two drifting into a trigger that can never fire.
        assertEquals(
            EasymaticAccessibilityService.FINGERPRINT_TRIGGER_TYPE,
            FingerprintGestureTrigger.TRIGGER_TYPE,
        )
        assertEquals(
            EasymaticAccessibilityService.KEY_TRIGGER_TYPE,
            FingerprintGestureTrigger.KEY_TRIGGER_TYPE,
        )
        assertEquals(EasymaticAccessibilityService.KEY_GESTURE, FingerprintGestureTrigger.KEY_GESTURE)
        assertEquals(
            listOf(
                EasymaticAccessibilityService.GESTURE_SWIPE_UP,
                EasymaticAccessibilityService.GESTURE_SWIPE_DOWN,
                EasymaticAccessibilityService.GESTURE_SWIPE_LEFT,
                EasymaticAccessibilityService.GESTURE_SWIPE_RIGHT,
            ),
            FingerprintGesture.entries.map { it.payloadValue },
        )
    }
}
