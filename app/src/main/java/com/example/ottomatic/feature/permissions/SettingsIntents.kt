package com.example.ottomatic.feature.permissions

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.net.toUri
import com.example.ottomatic.R
import com.example.ottomatic.core.permissions.PrerequisiteType

/**
 * The system pages where the grants Ottomatic needs are turned on and off.
 *
 * One table rather than two, shared by the node config card (through
 * [rememberPrerequisiteState]) and the permissions screen. Every one of these is
 * an *optional* system activity — some OEM builds and most emulators are missing
 * one or another — so they all go through [startSettings], whose `runCatching`
 * is the difference between a missing page and a crash.
 */

/** Sends the user to the page where [type] is granted. */
@Suppress("ReturnCount") // One early exit per type with no page to open.
internal fun Context.openSettingsFor(type: PrerequisiteType) {
    val intent = when (type) {
        PrerequisiteType.ACCESSIBILITY_SERVICE ->
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        PrerequisiteType.NOTIFICATION_LISTENER -> notificationListenerIntent()
        PrerequisiteType.NOTIFICATION_POLICY ->
            Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
        // The `package:` uri is what opens *our* row rather than the full list of
        // every app on the phone.
        PrerequisiteType.OVERLAY ->
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, packageUri())
        PrerequisiteType.BATTERY_OPTIMISATION ->
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, packageUri())
        PrerequisiteType.WRITE_SETTINGS ->
            Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, packageUri())
        // System-wide and with no per-app row, so no `package:` uri: this is the
        // one toggle here that is not about Ottomatic at all. It also toggles both
        // ways, which is why `openRevokeFor` needs no branch for it.
        PrerequisiteType.NFC -> Intent(Settings.ACTION_NFC_SETTINGS)
        // Nothing to open below API 31, on EXACT_ALARM's reasoning: media management
        // does not exist there, so there is no page and nothing to grant. Note this
        // page lists every app that *could* manage media, with no `package:` uri
        // accepted — unlike the overlay and write-settings pages above.
        PrerequisiteType.MANAGE_MEDIA ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Intent(Settings.ACTION_REQUEST_MANAGE_MEDIA, packageUri())
            } else {
                return
            }
        // Nothing to open below API 31: an exact alarm needs no permission there,
        // so there is no page and nothing to grant.
        PrerequisiteType.EXACT_ALARM ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, packageUri())
            } else {
                return
            }
        // Deliberately *not* the grant dialog. That is ACTION_ADD_DEVICE_ADMIN, which
        // is the one intent here that cannot be started as a new task at all — see
        // [deviceAdminIntent] — so it goes through a launcher rather than this table.
        // What is left is the page the administrator is listed on, which is both where
        // it is turned off and somewhere useful for a caller holding no launcher.
        PrerequisiteType.DEVICE_ADMIN -> Intent(Settings.ACTION_SECURITY_SETTINGS)
        // Nothing to open: RUNTIME goes through the permission dialog, and no node
        // declares a foreground-service type.
        PrerequisiteType.RUNTIME,
        PrerequisiteType.FOREGROUND_SERVICE,
        -> return
    }
    startSettings(intent)
}

/**
 * Sends the user to the page where [type] is turned *off*.
 *
 * The same page as [openSettingsFor] everywhere but one, which is why this is a
 * function rather than a flag: `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` is
 * a grant-only dialog offering "Allow" and nothing else, so a Revoke that opened
 * it would be a dead end. The list page is the only place the exemption can be
 * taken back.
 */
internal fun Context.openRevokeFor(type: PrerequisiteType) {
    if (type == PrerequisiteType.BATTERY_OPTIMISATION) {
        startSettings(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        return
    }
    // Device admin needs no branch, unlike the entry above: what [openSettingsFor]
    // opens for it is already the list page rather than the grant dialog. Removing it
    // there is a switch on that page — `removeActiveAdmin` would work too, since an app
    // may always remove its own administrator, and is deliberately not used: it would be
    // the app's only revoke that happens inside Ottomatic, and both callers re-read grant
    // state on ON_RESUME, so a revoke that never leaves the app would leave the row
    // showing a tick that is no longer true.
    openSettingsFor(type)
}

/**
 * Opens this app's row in system Settings — where a runtime permission is
 * revoked, and the only route left once Android has stopped showing its dialog.
 *
 * There is no API to revoke a permission from inside an app (`revokeSelfPermissionOnKill`
 * exists from API 33, but applies by killing the process, which drops the user
 * out of Ottomatic mid-tap). So Revoke means "take you to where it is done".
 */
internal fun Context.openAppDetailsSettings() {
    startSettings(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri()))
}

/**
 * Deep-links straight to our own row where the platform allows it (API 30+);
 * older versions only offer the full list.
 */
private fun Context.notificationListenerIntent(): Intent =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).putExtra(
            Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
            ComponentName(packageName, NOTIFICATION_LISTENER_CLASS).flattenToString(),
        )
    } else {
        Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
    }

/**
 * The dialog that turns Ottomatic into a device administrator.
 *
 * **Handed back rather than started**, which is the one thing about it that is not
 * like every other entry in this file. Settings refuses this action outright when it
 * arrives with `FLAG_ACTIVITY_NEW_TASK` — "Cannot start ADD_DEVICE_ADMIN as a new
 * task" — and finishes without drawing anything, which on screen is indistinguishable
 * from a dead button. [startSettings] adds that flag to everything, correctly, because
 * a Settings *page* started from a non-activity context needs it. So this one cannot go
 * through it: it has to be launched from an Activity, for a result, which is also what
 * the platform documents for it.
 *
 * [DevicePolicyManager.EXTRA_ADD_EXPLANATION] is the app's own sentence on a dialog
 * otherwise written entirely by Android, and the only place the user is told *why*
 * before agreeing.
 */
internal fun Context.deviceAdminIntent(): Intent =
    Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
        .putExtra(
            DevicePolicyManager.EXTRA_DEVICE_ADMIN,
            ComponentName(packageName, DEVICE_ADMIN_RECEIVER_CLASS),
        )
        .putExtra(
            DevicePolicyManager.EXTRA_ADD_EXPLANATION,
            getString(R.string.perm_explanation_device_admin),
        )

private fun Context.packageUri() = "package:$packageName".toUri()

/**
 * The `runCatching` is the difference between a missing page and a crash — and the
 * log line is the difference between a missing page and a button that looks broken.
 * A swallowed failure here reads exactly like a dead button, and there is nowhere
 * else it could be reported from.
 */
private fun Context.startSettings(intent: Intent) {
    runCatching { startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        .onFailure { Log.w("SettingsIntents", "No system page for ${intent.action}", it) }
}

private const val NOTIFICATION_LISTENER_CLASS =
    "com.example.ottomatic.data.trigger.NotificationListener"

private const val DEVICE_ADMIN_RECEIVER_CLASS =
    "com.example.ottomatic.data.trigger.LoginAdminReceiver"
