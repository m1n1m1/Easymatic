package com.example.ottomatic.feature.permissions

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.core.net.toUri
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
        // Nothing to open: RUNTIME goes through the permission dialog, and no
        // node declares the other two.
        PrerequisiteType.RUNTIME,
        PrerequisiteType.FOREGROUND_SERVICE,
        PrerequisiteType.DEVICE_ADMIN,
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

private fun Context.packageUri() = "package:$packageName".toUri()

private fun Context.startSettings(intent: Intent) {
    runCatching { startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

private const val NOTIFICATION_LISTENER_CLASS =
    "com.example.ottomatic.data.trigger.NotificationListener"
