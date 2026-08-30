package io.github.m1n1m1.easymatic.feature.permissions

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.m1n1m1.easymatic.core.permissions.PrerequisiteType
import io.github.m1n1m1.easymatic.data.permissions.AndroidPermissionChecker

/**
 * Whether a prerequisite that is granted on a system Settings page is currently
 * satisfied, and how to send the user there.
 *
 * The counterpart to [PermissionState], which covers permissions granted through
 * the runtime dialog. The difference that matters is that there is usually no result
 * callback to listen to: the user leaves the app entirely, toggles a switch, and
 * comes back. So this re-reads on `ON_RESUME`, the same way [PermissionState]
 * does after its own dialog.
 *
 * `DEVICE_ADMIN` is the exception and needs the launcher below, because its grant is a
 * dialog started *for a result* rather than a page: Settings drops it on the floor when
 * it arrives as a new task, which is what a bare `startActivity` from a context has to
 * ask for. See `deviceAdminIntent`.
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

    // One launcher for the notice, not one per requirement: this composable is called
    // once per prerequisite a node declares, and each call remembers its own.
    val adminLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { revision++ }

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
            onOpenSettings = {
                if (type == PrerequisiteType.DEVICE_ADMIN) {
                    adminLauncher.launch(context.deviceAdminIntent())
                } else {
                    context.openSettingsFor(type)
                }
            },
        )
    }
}

