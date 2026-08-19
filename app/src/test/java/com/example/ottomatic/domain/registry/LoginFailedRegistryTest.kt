package com.example.ottomatic.domain.registry

import com.example.ottomatic.core.model.ConfigKey
import com.example.ottomatic.core.model.PortName
import com.example.ottomatic.core.permissions.PrerequisiteType
import com.example.ottomatic.data.permissions.AndroidPermissionChecker
import com.example.ottomatic.data.trigger.LoginAdminReceiver
import com.example.ottomatic.domain.model.Direction
import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.PortKind
import com.example.ottomatic.engine.trigger.LoginFailedTrigger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Registry and schema contract for `trigger.login_failed`. */
class LoginFailedRegistryTest {

    @Test
    fun `the trigger is registered under Device State`() {
        assertNotNull(TriggerRegistry.byId(LoginFailedTrigger.TYPE_ID))
        val definition = NodeTypeRegistry.byId(LoginFailedTrigger.TYPE_ID)
        assertNotNull(definition)
        // Beside screen, dock and user-present: the lock screen is device state.
        assertEquals(NodeCategory.DEVICE_STATE, definition!!.category)
    }

    @Test
    fun `it exposes one exec out, the attempt struct and the count beside it`() {
        val definition = NodeTypeRegistry.byId(LoginFailedTrigger.TYPE_ID)!!
        val exec = definition.ports.filter { it.kind == PortKind.EXECUTION }
        val dataOut = definition.ports.filter { it.kind == PortKind.DATA && it.direction == Direction.OUT }

        assertEquals(1, exec.size)
        // The count is a derived port rather than something to break out of the struct:
        // comparing it is the whole point of the node.
        assertEquals(listOf(PortName("attempt"), PortName("attempts")), dataOut.map { it.name })
        assertTrue(definition.ports.none { it.kind == PortKind.DATA && it.direction == Direction.IN })
    }

    @Test
    fun `it declares the device admin prerequisite with a rationale`() {
        val requirement =
            NodeTypeRegistry.byId(LoginFailedTrigger.TYPE_ID)!!.permissionRequirements.single()

        assertEquals(PrerequisiteType.DEVICE_ADMIN, requirement.type)
        // No android.permission.* name exists for this; it is an admin activation.
        assertNull(requirement.manifestPermission)
        // Without a rationale key the editor renders no card at all, and the user
        // would be sent to a system dialog with no idea what it is for.
        assertTrue(requirement.rationaleKey.isNotBlank())
    }

    @Test
    fun `the permission checker looks for the receiver that actually exists`() {
        // The checker names the receiver by string so that permission checking does
        // not depend on the receiver class. This is what keeps the two in step; a
        // rename or package move breaks here rather than silently reporting the
        // prerequisite as never granted — which would badge the node forever.
        assertEquals(
            LoginAdminReceiver::class.java.name,
            AndroidPermissionChecker.DEVICE_ADMIN_RECEIVER_CLASS,
        )
    }

    @Test
    fun `it defaults to every failed attempt`() {
        // Somebody placing this node means "tell me when an unlock fails". A higher
        // default would make the commonest case something you have to go and find.
        val schema = ConfigSchemaRegistry.byId(LoginFailedTrigger.TYPE_ID)!!
        val field = schema.fields.single { it.key == ConfigKey("afterAttempts") }
        assertEquals("1", field.defaultValue)
    }
}
