package io.github.m1n1m1.easymatic.engine.action

import io.github.m1n1m1.easymatic.core.permissions.PermissionRequirement
import io.github.m1n1m1.easymatic.core.permissions.Permissions
import io.github.m1n1m1.easymatic.core.permissions.PrerequisiteType

internal val WRITE_SETTINGS_PERMISSION = PermissionRequirement(
    manifestPermission = null,
    type = PrerequisiteType.WRITE_SETTINGS,
    rationaleKey = "settings.write",
)

internal val DND_POLICY_PERMISSION = PermissionRequirement(
    manifestPermission = null,
    type = PrerequisiteType.NOTIFICATION_POLICY,
    rationaleKey = "dnd.policy",
)

internal val BLUETOOTH_PERMISSION = PermissionRequirement(
    manifestPermission = Permissions.BLUETOOTH_CONNECT.manifest,
    type = PrerequisiteType.RUNTIME,
    rationaleKey = "bluetooth.connect",
)

internal val TORCH_CAMERA_PERMISSION = PermissionRequirement(
    manifestPermission = Permissions.CAMERA.manifest,
    type = PrerequisiteType.RUNTIME,
    rationaleKey = "camera.photo",
)
