package com.example.ottomatic.core.permissions

/**
 * A manifest permission name. Wrapped in a value class so permission constants
 * are typed and discoverable, and so callers don't pass arbitrary strings
 * where a permission is expected.
 */
@JvmInline
value class Permission(val manifest: String) {
    /** Last segment of the manifest name, e.g. `ACCESS_FINE_LOCATION`. */
    val simpleName: String get() = manifest.substringAfterLast('.')
}

/**
 * Catalogue of permissions used across Ottomatic. Centralised here so the
 * permission request flow and the trigger that depends on each permission
 * reference the same constant.
 */
object Permissions {
    val RECEIVE_SMS = Permission("android.permission.RECEIVE_SMS")
    val POST_NOTIFICATIONS = Permission("android.permission.POST_NOTIFICATIONS")
    val ACCESS_FINE_LOCATION = Permission("android.permission.ACCESS_FINE_LOCATION")
    val ACCESS_COARSE_LOCATION = Permission("android.permission.ACCESS_COARSE_LOCATION")
    val ACCESS_BACKGROUND_LOCATION = Permission("android.permission.ACCESS_BACKGROUND_LOCATION")

    /**
     * Do-Not-Disturb policy access. Not a standard runtime permission — the
     * user must grant it on the `ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS`
     * page (see `MainActivity.requestDndPermissionIfNeeded`). The
     * [PermissionChecker] handles this name specially via
     * `NotificationManager.isNotificationPolicyAccessGranted`.
     */
    val ACCESS_NOTIFICATION_POLICY = Permission("android.permission.ACCESS_NOTIFICATION_POLICY")
}
