package com.example.ottomatic.core.permissions

/**
 * Checks the current status of a runtime [Permission]. Implemented by
 * [com.example.ottomatic.data.permissions.AndroidPermissionChecker] in `data/`,
 * which delegates to `ContextCompat.checkSelfPermission` and (when constructed
 * with an Activity) `shouldShowRequestPermissionRationale`.
 *
 * Requesting a permission is the responsibility of the calling Activity via
 * `ActivityResultContracts.RequestMultiplePermissions` — this interface only
 * reports the current status so callers can decide whether to launch a request.
 */
interface PermissionChecker {
    fun status(permission: Permission): PermissionStatus

    /**
     * Whether a prerequisite that is *not* granted through the runtime dialog is
     * currently satisfied — accessibility access, notification-listener access,
     * and so on.
     *
     * These have no `android.permission.*` name to check, so they cannot go
     * through [status]. Each has its own system API instead, which is why this
     * is keyed by [PrerequisiteType] rather than by [Permission].
     *
     * The default reports everything as unsatisfied. That is the safe direction:
     * a node whose prerequisite cannot be verified is shown as needing
     * attention rather than silently claimed to be working.
     */
    fun isPrerequisiteSatisfied(type: PrerequisiteType): Boolean = false
}

/** True when every permission in [permissions] is [PermissionStatus.Granted]. */
fun PermissionChecker.areAllGranted(permissions: Iterable<Permission>): Boolean =
    permissions.all { status(it) is PermissionStatus.Granted }
