package com.example.ottomatic.engine

import com.example.ottomatic.core.model.ConfigKey
import com.example.ottomatic.core.model.NodeId
import com.example.ottomatic.core.model.NodeTypeId
import com.example.ottomatic.core.permissions.Permission
import com.example.ottomatic.core.permissions.PermissionChecker
import com.example.ottomatic.core.permissions.PermissionStatus
import com.example.ottomatic.core.permissions.Permissions
import com.example.ottomatic.core.permissions.PrerequisiteType
import com.example.ottomatic.domain.model.WorkflowNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers prerequisites that are granted on a Settings page rather than through
 * the runtime dialog.
 *
 * These used to be reported as unsatisfied unconditionally, which meant
 * `trigger.notification` could never be shown as ready however many times the
 * user had switched notification access on.
 */
class PermissionControllerTest {

    private val notificationNode = WorkflowNode(
        id = NodeId("n1"),
        typeId = NodeTypeId("trigger.notification"),
        name = "Notification",
        x = 0f,
        y = 0f,
        config = emptyMap<ConfigKey, String>(),
    )

    @Test
    fun `a granted settings prerequisite counts as satisfied`() {
        val controller = PermissionController(FakeChecker(satisfied = setOf(NOTIFICATION_LISTENER)))

        assertTrue(controller.allSatisfied(listOf(notificationNode)))
    }

    @Test
    fun `an ungranted settings prerequisite is reported`() {
        val controller = PermissionController(FakeChecker(satisfied = emptySet()))

        val unsatisfied = controller.unsatisfiedFor(listOf(notificationNode))

        assertEquals(1, unsatisfied.size)
        assertEquals(NOTIFICATION_LISTENER, unsatisfied.single().type)
    }

    @Test
    fun `the default checker reports settings prerequisites as unsatisfied`() {
        // The safe direction: something that cannot be verified is shown as
        // needing attention rather than claimed to be working.
        val bare = object : PermissionChecker {
            override fun status(permission: Permission) = PermissionStatus.Granted
        }

        assertFalse(PermissionController(bare).allSatisfied(listOf(notificationNode)))
    }

    /**
     * Until `actionNode` grew a `permissions` parameter, only triggers could declare
     * one — so `action.call` returned false without `CALL_PHONE` and nothing in the
     * app, this controller included, could say so.
     */
    @Test
    fun `an action's declared runtime permission is reported`() {
        val call = WorkflowNode(
            id = NodeId("n2"),
            typeId = NodeTypeId("action.call"),
            name = "Call",
            x = 0f,
            y = 0f,
        )
        val controller = PermissionController(
            FakeChecker(satisfied = emptySet(), granted = emptySet()),
        )

        val unsatisfied = controller.unsatisfiedFor(listOf(call))

        assertEquals(
            listOf(Permissions.CALL_PHONE.manifest),
            unsatisfied.mapNotNull { it.manifestPermission },
        )
    }

    private companion object {
        val NOTIFICATION_LISTENER = PrerequisiteType.NOTIFICATION_LISTENER
    }
}

private class FakeChecker(
    private val satisfied: Set<PrerequisiteType>,
    private val granted: Set<String>? = null,
) : PermissionChecker {

    override fun status(permission: Permission): PermissionStatus =
        if (granted == null || permission.manifest in granted) {
            PermissionStatus.Granted
        } else {
            PermissionStatus.Denied(showRationale = false)
        }

    override fun isPrerequisiteSatisfied(type: PrerequisiteType): Boolean = type in satisfied
}
