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
fun interface PermissionChecker {
    fun status(permission: Permission): PermissionStatus
}

/** True when every permission in [permissions] is [PermissionStatus.Granted]. */
fun PermissionChecker.areAllGranted(permissions: Iterable<Permission>): Boolean =
    permissions.all { status(it) is PermissionStatus.Granted }
