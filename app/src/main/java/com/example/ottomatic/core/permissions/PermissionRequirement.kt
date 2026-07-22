package com.example.ottomatic.core.permissions

/**
 * The kind of grant a [PermissionRequirement] needs. Most permissions use the
 * standard [RUNTIME] dialog; some require a dedicated system flow.
 */
enum class PrerequisiteType {
    /** Standard `requestPermissions` runtime dialog. */
    RUNTIME,

    /** DND policy access — granted on the system Settings page. */
    NOTIFICATION_POLICY,

    /** Notification listener access — granted on the system Settings page. */
    NOTIFICATION_LISTENER,

    /** Foreground service type (e.g. location/sensor) — declared at runtime. */
    FOREGROUND_SERVICE,

    /** Accessibility service — granted on the system Settings page. */
    ACCESSIBILITY_SERVICE,

    /** Device administrator — granted via `DeviceAdminReceiver` enabling. */
    DEVICE_ADMIN,
}

/**
 * A permission prerequisite attached to a
 * [com.example.ottomatic.domain.model.NodeTypeDefinition].
 *
 * Each trigger/action declares the permissions it needs so the editor and the
 * [com.example.ottomatic.engine.WorkflowRunner] can verify them before a macro
 * is armed. [manifestPermission] is the `android.permission.*` name (or null
 * for non-runtime prerequisites such as accessibility); [type] selects the
 * grant flow the UI must drive.
 *
 * [rationaleKey] is a key the UI layer maps to a localised rationale string.
 */
data class PermissionRequirement(
    val manifestPermission: String?,
    val type: PrerequisiteType,
    val rationaleKey: String = "",
)
