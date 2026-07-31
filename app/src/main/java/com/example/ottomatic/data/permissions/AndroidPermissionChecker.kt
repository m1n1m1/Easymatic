package com.example.ottomatic.data.permissions

import android.app.Activity
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.ottomatic.core.permissions.Permission
import com.example.ottomatic.core.permissions.PermissionChecker
import com.example.ottomatic.core.permissions.PermissionStatus
import com.example.ottomatic.core.permissions.Permissions
import com.example.ottomatic.core.permissions.PrerequisiteType

/**
 * Android implementation of [PermissionChecker]. When constructed with an
 * [Activity] it can populate [PermissionStatus.Denied.showRationale] via
 * [ActivityCompat.shouldShowRequestPermissionRationale]; with a bare [Context]
 * (e.g. application context) the rationale flag is always false.
 *
 * Special permissions that are not granted through the standard runtime dialog
 * are handled here: `ACCESS_NOTIFICATION_POLICY` is checked via
 * [NotificationManager.isNotificationPolicyAccessGranted] instead of
 * [ContextCompat.checkSelfPermission].
 */
class AndroidPermissionChecker(
    private val context: Context,
    private val activity: Activity? = null,
) : PermissionChecker {

    override fun status(permission: Permission): PermissionStatus {
        if (permission == Permissions.ACCESS_NOTIFICATION_POLICY) {
            return dndPolicyStatus()
        }
        val granted = ContextCompat.checkSelfPermission(
            context,
            permission.manifest,
        ) == PackageManager.PERMISSION_GRANTED
        return if (granted) {
            PermissionStatus.Granted
        } else {
            PermissionStatus.Denied(
                showRationale = activity?.let {
                    ActivityCompat.shouldShowRequestPermissionRationale(it, permission.manifest)
                } ?: false,
            )
        }
    }

    /**
     * Each of these has its own system API — there is no `android.permission.*`
     * name to check — so they are answered here rather than through [status].
     */
    override fun isPrerequisiteSatisfied(type: PrerequisiteType): Boolean = when (type) {
        PrerequisiteType.RUNTIME -> false
        PrerequisiteType.NOTIFICATION_POLICY -> dndPolicyStatus() is PermissionStatus.Granted
        PrerequisiteType.NOTIFICATION_LISTENER ->
            NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
        PrerequisiteType.ACCESSIBILITY_SERVICE -> isAccessibilityServiceEnabled()
        // Neither is declared by any node; reporting them unsatisfied keeps the
        // safe default rather than claiming something unverified is working.
        PrerequisiteType.FOREGROUND_SERVICE, PrerequisiteType.DEVICE_ADMIN -> false
    }

    /**
     * Whether *our* accessibility service is among the enabled ones.
     *
     * The enabled list is a colon-separated set of flattened component names, so
     * it is parsed rather than substring-matched: a `contains(packageName)` test
     * would also match a different app whose package name merely starts with
     * ours. The master switch is checked too, because a service can remain listed
     * while accessibility as a whole is off.
     */
    private fun isAccessibilityServiceEnabled(): Boolean {
        val resolver = context.contentResolver
        val masterSwitch = Settings.Secure.getInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0)
        if (masterSwitch != 1) return false
        val enabled = Settings.Secure.getString(
            resolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ).orEmpty()
        val ours = ComponentName(context.packageName, ACCESSIBILITY_SERVICE_CLASS)
        return enabled.split(':')
            .mapNotNull { ComponentName.unflattenFromString(it) }
            .any { it == ours }
    }

    private fun dndPolicyStatus(): PermissionStatus {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE)
            as NotificationManager
        return if (notificationManager.isNotificationPolicyAccessGranted) {
            PermissionStatus.Granted
        } else {
            PermissionStatus.Denied(showRationale = false)
        }
    }

    companion object {
        /**
         * Fully-qualified name of the app's accessibility service.
         *
         * Named rather than referenced so that `core`/`data` permission checking
         * does not depend on the service class itself;
         * `AccessibilityServiceNameTest` asserts the two stay in step.
         */
        const val ACCESSIBILITY_SERVICE_CLASS =
            "com.example.ottomatic.data.accessibility.OttomaticAccessibilityService"
    }
}
