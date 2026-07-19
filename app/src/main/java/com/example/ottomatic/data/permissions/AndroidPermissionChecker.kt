package com.example.ottomatic.data.permissions

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.ottomatic.core.permissions.Permission
import com.example.ottomatic.core.permissions.PermissionChecker
import com.example.ottomatic.core.permissions.PermissionStatus

/**
 * Android implementation of [PermissionChecker]. When constructed with an
 * [Activity] it can populate [PermissionStatus.Denied.showRationale] via
 * [ActivityCompat.shouldShowRequestPermissionRationale]; with a bare [Context]
 * (e.g. application context) the rationale flag is always false.
 */
class AndroidPermissionChecker(
    private val context: Context,
    private val activity: Activity? = null,
) : PermissionChecker {

    override fun status(permission: Permission): PermissionStatus {
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
}
