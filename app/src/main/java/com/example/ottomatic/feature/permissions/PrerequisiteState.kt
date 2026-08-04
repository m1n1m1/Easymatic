package com.example.ottomatic.feature.permissions

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.core.net.toUri
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.ottomatic.core.permissions.PrerequisiteType
import com.example.ottomatic.data.permissions.AndroidPermissionChecker

/**
 * Whether a prerequisite that is granted on a system Settings page is currently
 * satisfied, and how to send the user there.
 *
 * The counterpart to [PermissionState], which covers permissions granted through
 * the runtime dialog. The difference that matters is that there is no result
 * callback to listen to: the user leaves the app entirely, toggles a switch, and
 * comes back. So this re-reads on `ON_RESUME`, the same way [PermissionState]
 * does after its own dialog.
 */
class PrerequisiteState internal constructor(
    val type: PrerequisiteType,
    val isSatisfied: Boolean,
    private val onOpenSettings: () -> Unit,
) {
    /** Opens the system page where this prerequisite is granted. */
    fun openSettings() = onOpenSettings()
}

@Composable
fun rememberPrerequisiteState(type: PrerequisiteType): PrerequisiteState {
    val context = LocalContext.current

    var revision by remember { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) revision++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    return remember(context, type, revision) {
        PrerequisiteState(
            type = type,
            isSatisfied = AndroidPermissionChecker(context).isPrerequisiteSatisfied(type),
            onOpenSettings = { context.openSettingsFor(type) },
        )
    }
}

/**
 * Sends the user to the page where [type] is granted.
 *
 * Wrapped in `runCatching` because these are all optional system activities:
 * some OEM builds and most emulators are missing one or another, and an
 * unhandled `ActivityNotFoundException` would take the editor down with it.
 */
private fun Context.openSettingsFor(type: PrerequisiteType) {
    val intent = when (type) {
        PrerequisiteType.ACCESSIBILITY_SERVICE ->
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        PrerequisiteType.NOTIFICATION_LISTENER -> notificationListenerIntent()
        PrerequisiteType.NOTIFICATION_POLICY ->
            Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
        // The `package:` uri is what opens *our* row rather than the full list of
        // every app on the phone.
        PrerequisiteType.OVERLAY ->
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:$packageName".toUri())
        // Nothing to open: RUNTIME goes through the permission dialog, and no
        // node declares the other two.
        PrerequisiteType.RUNTIME,
        PrerequisiteType.FOREGROUND_SERVICE,
        PrerequisiteType.DEVICE_ADMIN,
        -> return
    }
    runCatching { startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
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

private const val NOTIFICATION_LISTENER_CLASS =
    "com.example.ottomatic.data.trigger.NotificationListener"
