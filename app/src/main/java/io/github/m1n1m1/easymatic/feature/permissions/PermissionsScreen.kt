package io.github.m1n1m1.easymatic.feature.permissions

import androidx.annotation.StringRes
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import io.github.m1n1m1.easymatic.R
import io.github.m1n1m1.easymatic.core.permissions.Permission
import io.github.m1n1m1.easymatic.core.permissions.PermissionChecker
import io.github.m1n1m1.easymatic.core.permissions.Permissions
import io.github.m1n1m1.easymatic.core.permissions.PrerequisiteType
import io.github.m1n1m1.easymatic.core.permissions.isSatisfied
import io.github.m1n1m1.easymatic.domain.registry.NodeTypeRegistry
import io.github.m1n1m1.easymatic.domain.registry.PermissionCatalogue
import io.github.m1n1m1.easymatic.feature.i18n.rememberNodeText
import io.github.m1n1m1.easymatic.domain.registry.PermissionEntry
import io.github.m1n1m1.easymatic.feature.SettingsTopBar
import io.github.m1n1m1.easymatic.feature.grapheditor.EditorColors
import io.github.m1n1m1.easymatic.feature.macro.editorTextButtonColors

/**
 * Every grant Easymatic can need, what state it is in, and a way to change it.
 *
 * The gap this fills: a missing permission is the one failure that looks exactly
 * like a working macro, and until now it was only ever mentioned *inside* a
 * node's config form — visible to somebody who thought to open that node, which
 * is precisely what you do not do when the macro looks fine. Several grants the
 * app genuinely uses were not visible even there, because no node declares them
 * (see [PermissionCatalogue.appLevel]).
 *
 * Takes a [PermissionChecker] rather than building one, so this file stays on
 * the right side of `feature ← domain + engine + core`; `MainActivity` is the
 * composition root and hands it the one from `ServiceLocator`.
 *
 * It reads that checker directly and **never** `GrantedPrerequisites`, which
 * answers "granted" while unhydrated on purpose — here that would render every
 * row as fine on the first frame after process start, which is the exact
 * opposite of what this screen is for.
 */
@Composable
fun PermissionsScreen(checker: PermissionChecker, onBack: () -> Unit) {
    val state = rememberPermissionsUiState(checker)
    val nodeEntries = state.entries.filterNot { it.isAppLevel }
    val appEntries = state.entries.filter { it.isAppLevel }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(EditorColors.canvasBackground),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            SettingsTopBar(
                title = stringResource(R.string.permissions_permissions),
                contentDescription = stringResource(R.string.permissions_back),
                onBack = onBack,
            )

            // Hoisted: LazyColumn's content lambda is LazyListScope, not a composition,
            // so a stringResource call cannot live inside it.
            val nodesTitle = stringResource(R.string.permissions_used_by_your_nodes)
            val appTitle = stringResource(R.string.permissions_easymatic_itself)
            val nodesBlurb = stringResource(R.string.permissions_node_section_blurb)
            val appBlurb = stringResource(R.string.permissions_app_section_blurb)
            LazyColumn(modifier = Modifier.fillMaxSize().navigationBarsPadding()) {
                section(
                    id = "nodes",
                    title = nodesTitle,
                    blurb = nodesBlurb,
                    entries = nodeEntries,
                    state = state,
                )
                section(
                    id = "app",
                    title = appTitle,
                    blurb = appBlurb,
                    entries = appEntries,
                    state = state,
                )
            }
        }
    }
}

private fun LazyListScope.section(
    id: String,
    title: String,
    blurb: String,
    entries: List<PermissionEntry>,
    state: PermissionsUiState,
) {
    if (entries.isEmpty()) return
    item(key = "header-$id") { SectionHeader(title = title, blurb = blurb) }
    items(entries, key = { "$id-${it.key}" }) { entry ->
        PermissionRow(
            entry = entry,
            isGranted = state.isGranted(entry),
            actionLabel = state.actionLabelFor(entry),
            onAction = { state.act(entry) },
        )
        HorizontalDivider(color = EditorColors.chromeBorder, thickness = 1.dp)
    }
}

@Composable
private fun SectionHeader(title: String, blurb: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 18.dp, end = 18.dp, top = 22.dp, bottom = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = title.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = EditorColors.textSecondary,
        )
        Text(text = blurb, fontSize = 12.sp, color = EditorColors.textSecondary)
    }
}

@Composable
private fun PermissionRow(
    entry: PermissionEntry,
    isGranted: Boolean,
    @StringRes actionLabel: Int,
    onAction: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Deliberately not a green tick: the palette has no green, and a screen of
        // bright ticks buries the one or two rows that actually need attention.
        // Amber is already what this app means by "look at this".
        Icon(
            imageVector = if (isGranted) Icons.Filled.CheckCircle else Icons.Filled.WarningAmber,
            contentDescription = stringResource(
                if (isGranted) R.string.permissions_granted else R.string.permissions_not_granted,
            ),
            tint = if (isGranted) EditorColors.textSecondary else EditorColors.warnAccent,
            modifier = Modifier.size(18.dp),
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 14.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = titleRes(entry.requirement)
                    ?.let { stringResource(it) }
                    ?: derivedTitle(entry.requirement.manifestPermission),
                color = EditorColors.textPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = stringResource(descriptionRes(entry.requirement) ?: R.string.perm_desc_unknown),
                color = EditorColors.textSecondary,
                fontSize = 12.sp,
            )
            hintFor(entry)?.takeIf { !isGranted }?.let { hint ->
                Text(text = stringResource(hint), color = EditorColors.warnAccent, fontSize = 12.sp)
            }
            if (entry.neededBy.isNotEmpty()) {
                val nodeText = rememberNodeText()
                val names = entry.neededBy
                    .mapNotNull { NodeTypeRegistry.byId(it) }
                    .joinToString(", ") { nodeText.name(it) }
                Text(
                    text = stringResource(R.string.permissions_needed_by, names),
                    color = EditorColors.textSecondary,
                    fontSize = 12.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        TextButton(
            onClick = onAction,
            // A Revoke sitting on every correctly-configured row must not read as
            // the thing to do; the accent belongs on the rows still asking for
            // something.
            colors = if (isGranted) {
                ButtonDefaults.textButtonColors(contentColor = EditorColors.textSecondary)
            } else {
                editorTextButtonColors()
            },
        ) {
            Text(text = stringResource(actionLabel))
        }
    }
}

/**
 * The screen's whole state: what to show, and the three things a row can do.
 *
 * One of these for the screen rather than a [PermissionState] per row. Each of
 * those installs a lifecycle observer *and* an activity-result launcher, and a
 * launcher created inside a `LazyColumn` item is unregistered when the item
 * scrolls off — so a permission dialog answered after a scroll would drop its
 * result. Per-row state is right in a node's config form, where there is one.
 */
@Stable
private class PermissionsUiState(
    val entries: List<PermissionEntry>,
    private val granted: Set<String>,
    private val stuck: Set<String>,
    private val onGrant: (PermissionEntry) -> Unit,
    private val onRevoke: (PermissionEntry) -> Unit,
) {
    fun isGranted(entry: PermissionEntry): Boolean = entry.key in granted

    /**
     * "Grant" is reserved for the case where a tap really does produce the system
     * dialog. Everything else says "Open settings", because the user has to find
     * a switch and being told so beforehand is the difference between a slow
     * journey and a broken button — including a runtime permission Android has
     * stopped prompting for, whose `launch()` returns instantly with no UI and
     * leaves the row looking dead.
     */
    @StringRes
    fun actionLabelFor(entry: PermissionEntry): Int = when {
        isGranted(entry) -> R.string.permissions_revoke
        // The one non-runtime exception, and it is the rule rather than a hole in it:
        // ACTION_ADD_DEVICE_ADMIN puts a system dialog on screen with an Activate
        // button, so there is no switch to go and find.
        entry.requirement.type == PrerequisiteType.DEVICE_ADMIN -> R.string.permissions_grant
        entry.requirement.type != PrerequisiteType.RUNTIME -> R.string.permissions_open_settings
        entry.key in stuck -> R.string.permissions_open_settings
        else -> R.string.permissions_grant
    }

    fun act(entry: PermissionEntry) {
        if (isGranted(entry)) onRevoke(entry) else onGrant(entry)
    }
}

@Composable
private fun rememberPermissionsUiState(checker: PermissionChecker): PermissionsUiState {
    val context = LocalContext.current
    val entries = remember { PermissionCatalogue.entries() }

    // Bumped by anything that could have changed a grant; `results` counts only
    // permission-dialog answers, which is what the background-location round has
    // to be sequenced against.
    var revision by remember { mutableIntStateOf(0) }
    var results by remember { mutableIntStateOf(0) }
    var pendingBackground by remember { mutableStateOf(false) }
    var attempted by remember { mutableStateOf(emptySet<String>()) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        revision++
        results++
    }

    // Device admin is granted by a dialog started for a result rather than by a page,
    // so it cannot go through `openSettingsFor` — see `deviceAdminIntent`. One launcher
    // for the whole list, for the same reason as the one above.
    val adminLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { revision++ }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            // Grant state lives outside the snapshot system and every one of these
            // is granted by leaving the app, so returning to it is the only signal
            // there is.
            if (event == Lifecycle.Event.ON_RESUME) revision++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val granted = remember(revision) {
        entries.filter { checker.isSatisfied(it.requirement) }.mapTo(mutableSetOf()) { it.key }
    }

    // Round two of the background-location request: from API 30 the system
    // silently denies one bundled with foreground location, and its dialog only
    // makes sense once "while using the app" is already in hand.
    LaunchedEffect(results) {
        if (!pendingBackground) return@LaunchedEffect
        pendingBackground = false
        if (Permissions.ACCESS_FINE_LOCATION.manifest in granted) {
            launcher.launch(arrayOf(Permissions.ACCESS_BACKGROUND_LOCATION.manifest))
        }
    }

    return remember(entries, granted, attempted) {
        PermissionsUiState(
            entries = entries,
            granted = granted,
            stuck = attempted - granted,
            onGrant = { entry ->
                val manifest = entry.requirement.manifestPermission
                when {
                    entry.requirement.type == PrerequisiteType.DEVICE_ADMIN ->
                        adminLauncher.launch(context.deviceAdminIntent())
                    entry.requirement.type != PrerequisiteType.RUNTIME ->
                        context.openSettingsFor(entry.requirement.type)
                    manifest == null -> Unit
                    entry.key in attempted ->
                        // Android has already answered once and is not going to
                        // show the dialog again; the app's own page is all that
                        // is left.
                        context.openAppDetailsSettings()
                    Permission(manifest).isDeferred() &&
                        Permissions.ACCESS_FINE_LOCATION.manifest !in granted -> {
                        attempted = attempted + entry.key
                        pendingBackground = true
                        launcher.launch(foregroundLocation())
                    }
                    else -> {
                        attempted = attempted + entry.key
                        launcher.launch(arrayOf(manifest))
                    }
                }
            },
            onRevoke = { entry ->
                if (entry.requirement.type == PrerequisiteType.RUNTIME) {
                    context.openAppDetailsSettings()
                } else {
                    context.openRevokeFor(entry.requirement.type)
                }
            },
        )
    }
}

private fun foregroundLocation(): Array<String> = arrayOf(
    Permissions.ACCESS_FINE_LOCATION.manifest,
    Permissions.ACCESS_COARSE_LOCATION.manifest,
)

/**
 * The extra line a row needs when the grant is not a plain yes/no.
 *
 * Only background location has one: from API 30 Android does not offer it in a
 * dialog at all but sends the user to its own page, where "Allow all the time"
 * is one option among several and nothing on screen connects it back to the
 * geofence that wanted it.
 */
@StringRes
private fun hintFor(entry: PermissionEntry): Int? =
    if (entry.requirement.manifestPermission == Permissions.ACCESS_BACKGROUND_LOCATION.manifest &&
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
    ) {
        R.string.permissions_background_location_hint
    } else {
        null
    }
