package com.example.ottomatic.data.permissions

import android.app.Activity
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.ottomatic.core.permissions.Permission
import com.example.ottomatic.core.permissions.PermissionChecker
import com.example.ottomatic.core.permissions.PermissionStatus
import com.example.ottomatic.core.permissions.Permissions

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

    private fun dndPolicyStatus(): PermissionStatus {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE)
            as NotificationManager
        return if (notificationManager.isNotificationPolicyAccessGranted) {
            PermissionStatus.Granted
        } else {
            PermissionStatus.Denied(showRationale = false)
        }
    }
}
