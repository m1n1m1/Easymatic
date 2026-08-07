package com.example.ottomatic.feature.permissions

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

