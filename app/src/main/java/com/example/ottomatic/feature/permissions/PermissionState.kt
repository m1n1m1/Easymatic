package com.example.ottomatic.feature.permissions

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.ottomatic.core.permissions.Permission

/**
 * The grant state of a set of runtime permissions, and a way to ask for them,
 * for UI that has to *react* to permissions rather than fire-and-forget them
 * the way `MainActivity` does at startup.
 *
 * Grant state lives outside Compose's snapshot system, so it cannot be observed
 * — it is re-read on every recomposition triggered by a request result or by
 * the app returning to the foreground. That second case matters: the user may
 * have granted the permission in system Settings, especially background
 * location, which Android only lets us deep-link to rather than prompt for once
 * it has been denied twice.
 */
class PermissionState internal constructor(
    private val context: Context,
    private val permissions: List<Permission>,
    private val onRequest: () -> Unit,
) {
    /** The requested permissions that are not currently granted. */
    val missing: List<Permission> = permissions.filterNot { it.isGranted(context) }

    val allGranted: Boolean get() = missing.isEmpty()

    /** Shows the system prompt for whatever is still missing. */
    fun request() = onRequest()
}

/**
 * Remembers a [PermissionState] for [permissions].
 *
 * Background location is asked for in a second round, after everything else has
 * been granted: from API 30 the system silently denies a request that bundles
 * it with foreground location, and its dialog only makes sense once
 * "while using the app" is already in hand.
 */
// A List rather than a vararg: Permission is a value class, and Kotlin forbids
// those as vararg element types.
@Composable
fun rememberPermissionState(permissions: List<Permission>): PermissionState {
    val context = LocalContext.current
    val requested = remember(permissions) { permissions.toList() }

    var revision by remember { mutableIntStateOf(0) }
    var backgroundRound by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { revision++ }

    // Second round: only once the foreground permissions actually landed.
    LaunchedEffect(backgroundRound, revision) {
        if (!backgroundRound) return@LaunchedEffect
        backgroundRound = false
        val deferred = requested.filter { it.isDeferred() && !it.isGranted(context) }
        val blockers = requested.filter { !it.isDeferred() && !it.isGranted(context) }
        if (deferred.isNotEmpty() && blockers.isEmpty()) {
            launcher.launch(deferred.map { it.manifest }.toTypedArray())
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) revision++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    return remember(context, requested, revision) {
        PermissionState(
            context = context,
            permissions = requested,
            onRequest = {
                val immediate = requested.filter { !it.isDeferred() && !it.isGranted(context) }
                if (immediate.isNotEmpty()) {
                    launcher.launch(immediate.map { it.manifest }.toTypedArray())
                }
                // Either way, follow up on background once this round resolves.
                backgroundRound = true
            },
        )
    }
}

private fun Permission.isGranted(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, manifest) == PackageManager.PERMISSION_GRANTED

/**
 * True for permissions the system refuses to grant alongside others — currently
 * only background location, which must be requested on its own, after
 * foreground location has been granted.
 *
 * `internal` so the permissions screen, which drives its own launcher rather
 * than a [PermissionState] per row, shares this one definition of the rule
 * instead of restating it.
 */
internal fun Permission.isDeferred(): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
        manifest == android.Manifest.permission.ACCESS_BACKGROUND_LOCATION
