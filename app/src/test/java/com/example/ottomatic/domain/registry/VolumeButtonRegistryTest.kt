package com.example.ottomatic.domain.registry

import com.example.ottomatic.core.model.ConfigKey
import com.example.ottomatic.core.model.PortName
import com.example.ottomatic.core.permissions.PrerequisiteType
import com.example.ottomatic.data.accessibility.OttomaticAccessibilityService
import com.example.ottomatic.data.permissions.AndroidPermissionChecker
import com.example.ottomatic.domain.model.Direction
import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.PortKind
import com.example.ottomatic.engine.trigger.VolumeButtonTrigger
import com.example.ottomatic.engine.trigger.keys.KeyGesture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Registry and schema contract for `trigger.volume_button`. */
class VolumeButtonRegistryTest {

    @Test
    fun `the trigger is registered under Device State`() {
        assertNotNull(TriggerRegistry.byId(VolumeButtonTrigger.TYPE_ID))
        val definition = NodeTypeRegistry.byId(VolumeButtonTrigger.TYPE_ID)
        assertNotNull(definition)
        // A category of its own for a single node would be worse than sitting
        // with screen, dock and user-present.
        assertEquals(NodeCategory.DEVICE_STATE, definition!!.category)
    }

    @Test
    fun `it exposes one exec out and a SystemState data out`() {
        val definition = NodeTypeRegistry.byId(VolumeButtonTrigger.TYPE_ID)!!
        val exec = definition.ports.filter { it.kind == PortKind.EXECUTION }
        val dataOut = definition.ports.filter { it.kind == PortKind.DATA && it.direction == Direction.OUT }
        assertEquals(1, exec.size)
        assertEquals(1, dataOut.size)
        assertEquals(PortName("state"), dataOut.single().name)
        assertTrue(definition.ports.none { it.kind == PortKind.DATA && it.direction == Direction.IN })
    }

    @Test
    fun `it declares the accessibility prerequisite with a rationale`() {
        val requirements = NodeTypeRegistry.byId(VolumeButtonTrigger.TYPE_ID)!!.permissionRequirements
        val requirement = requirements.single()

        assertEquals(PrerequisiteType.ACCESSIBILITY_SERVICE, requirement.type)
        // No android.permission.* name exists for this; it is a Settings toggle.
        assertNull(requirement.manifestPermission)
        // Without a rationale key the editor renders no card at all, and the user
        // would be sent to a settings page with no idea what to switch on.
        assertTrue(requirement.rationaleKey.isNotBlank())
    }

    @Test
    fun `the permission checker looks for the service that actually exists`() {
        // The checker names the service by string so that permission checking
        // does not depend on the service class. This is what keeps the two in
        // step; a rename or package move breaks here rather than silently
        // reporting the prerequisite as never granted.
        assertEquals(
            OttomaticAccessibilityService::class.java.name,
            AndroidPermissionChecker.ACCESSIBILITY_SERVICE_CLASS,
        )
    }

    @Test
    fun `it defaults to a repeated press rather than a single one`() {
        // A single volume press is something the user does constantly for its
        // ordinary purpose; three in a row is a deliberate signal.
        val schema = ConfigSchemaRegistry.byId(VolumeButtonTrigger.TYPE_ID)!!
        val gesture = schema.fields.first { it.key == ConfigKey("gesture") }
        assertEquals(KeyGesture.SEQUENCE.name, gesture.defaultValue)
    }

    @Test
    fun `each gesture only shows the settings it uses`() {
        val schema = ConfigSchemaRegistry.byId(VolumeButtonTrigger.TYPE_ID)!!
        fun ruleFor(key: String) = schema.fields.first { it.key == ConfigKey(key) }.visibleWhen

        assertEquals(VisibilityRule(ConfigKey("gesture"), setOf("LONG_PRESS")), ruleFor("holdMs"))
        assertEquals(VisibilityRule(ConfigKey("gesture"), setOf("SEQUENCE")), ruleFor("presses"))
        assertEquals(VisibilityRule(ConfigKey("gesture"), setOf("SEQUENCE")), ruleFor("windowMs"))
    }
}
