package io.github.m1n1m1.easymatic.feature.setup

import android.content.Intent
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Mail
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.SettingsBackupRestore
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import io.github.m1n1m1.easymatic.R
import io.github.m1n1m1.easymatic.feature.grapheditor.EditorColors

/**
 * Everything that is set up once and shared by every macro.
 *
 * ### Why this is a screen at all
 *
 * These ten destinations used to hang off the workflow list's title row: two icon
 * buttons and an eight-item overflow menu, which that file's own comment had already
 * declared full. The overflow's real problem was not the count, though — it was that
 * a dropdown shows nothing until it is opened, so a Home Assistant connection or a
 * folder grant was something you had to already know was there. A tab lists them.
 *
 * ### The three groups
 *
 * **Connections** reach something outside the phone, and each can fail in ways the
 * user has to go and fix. **Libraries** are the user's own records, which macros
 * refer to by id. **System** is what the phone and other apps allow, which is not
 * something this app decides at all — it only reports it.
 *
 * Every row navigates to a screen that already existed and is unchanged; this screen
 * holds no state of its own. The subtitles are static and say what a screen is *for*,
 * not what it contains: a count would mean wiring ten repositories in here to tell
 * the user something the screen itself says the moment they open it.
 *
 * There is no back button, because a root tab has nothing above it.
 */
@Composable
@Suppress("LongParameterList") // One parameter per destination; the list is the screen.
fun SetupScreen(
    onOpenSmartHome: () -> Unit,
    onOpenAi: () -> Unit,
    onOpenMailAccounts: () -> Unit,
    onOpenVariables: () -> Unit,
    onOpenGeofences: () -> Unit,
    onOpenNfcTags: () -> Unit,
    onOpenFolders: () -> Unit,
    onOpenTranslationModels: () -> Unit,
    onOpenPermissions: () -> Unit,
    onOpenPlugins: () -> Unit,
    onOpenAppAccess: () -> Unit,
    onOpenBackup: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(EditorColors.canvasBackground),
    ) {
        Surface(color = EditorColors.chrome) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .height(BAR_HEIGHT)
                    .padding(horizontal = ROW_INSET),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.setup_title),
                    color = EditorColors.textPrimary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            item { SectionHeader(R.string.setup_section_connections) }
            item {
                // The subtitle earns its place here more than on any other row: Hue,
                // Home Assistant and MQTT are branches *inside* that one screen, so
                // without it nothing anywhere says Home Assistant is reachable.
                SetupRow(
                    icon = Icons.Filled.Lightbulb,
                    titleRes = R.string.workflowlist_smart_home,
                    subtitleRes = R.string.setup_smart_home_subtitle,
                    onClick = onOpenSmartHome,
                )
            }
            item {
                SetupRow(
                    icon = Icons.Filled.Psychology,
                    titleRes = R.string.setup_ai,
                    subtitleRes = R.string.setup_ai_subtitle,
                    onClick = onOpenAi,
                )
            }
            item {
                SetupRow(
                    icon = Icons.Filled.Mail,
                    titleRes = R.string.workflowlist_mail_accounts,
                    subtitleRes = R.string.setup_mail_subtitle,
                    onClick = onOpenMailAccounts,
                )
            }

            item { SectionHeader(R.string.workflowlist_libraries) }
            item {
                SetupRow(
                    icon = Icons.Filled.Tag,
                    titleRes = R.string.workflowlist_global_variables,
                    subtitleRes = R.string.setup_variables_subtitle,
                    onClick = onOpenVariables,
                )
            }
            item {
                SetupRow(
                    icon = Icons.Filled.Place,
                    titleRes = R.string.workflowlist_geofences,
                    subtitleRes = R.string.setup_geofences_subtitle,
                    onClick = onOpenGeofences,
                )
            }
            item {
                SetupRow(
                    icon = Icons.Filled.Nfc,
                    titleRes = R.string.workflowlist_nfc_tags,
                    subtitleRes = R.string.setup_nfc_subtitle,
                    onClick = onOpenNfcTags,
                )
            }
            item {
                // Not a library of records like the three above, but what the phone has
                // let this app reach. It belongs with them anyway: a macro names a
                // folder the same way it names a place.
                SetupRow(
                    icon = Icons.Filled.FolderOpen,
                    titleRes = R.string.files_folder_access,
                    subtitleRes = R.string.setup_folders_subtitle,
                    onClick = onOpenFolders,
                )
            }
            item {
                // Libraries rather than System, and the line is worth drawing: System is
                // what the phone and other apps *allow*, which this app only reports —
                // its three rows have nothing to delete. These models are this app's own
                // storage, downloaded on its own account, and a screen with a Delete
                // button is deciding something. It sits beside Folder access for the
                // reason given there: not a library of records, but a thing the app is
                // holding that a macro refers to by name.
                SetupRow(
                    icon = Icons.Filled.Translate,
                    titleRes = R.string.translate_models_title,
                    subtitleRes = R.string.setup_translation_models_subtitle,
                    onClick = onOpenTranslationModels,
                )
            }

            item { SectionHeader(R.string.setup_section_system) }
            item {
                SetupRow(
                    icon = Icons.Filled.Shield,
                    titleRes = R.string.workflowlist_permissions,
                    subtitleRes = R.string.setup_permissions_subtitle,
                    onClick = onOpenPermissions,
                )
            }
            item {
                SetupRow(
                    icon = Icons.Filled.Extension,
                    titleRes = R.string.workflowlist_plugins,
                    subtitleRes = R.string.setup_plugins_subtitle,
                    onClick = onOpenPlugins,
                )
            }
            item {
                // Beside Plugins because it is the same subject from the other side:
                // both are about other people's apps rather than the user's records.
                SetupRow(
                    icon = Icons.Filled.Key,
                    titleRes = R.string.workflowlist_app_access,
                    subtitleRes = R.string.setup_app_access_subtitle,
                    onClick = onOpenAppAccess,
                )
            }

            item { SectionHeader(R.string.setup_section_data) }
            item {
                // Neither a Library (nothing a macro refers to by id) nor System (this app
                // decides it, the phone does not): the one row about the app's own data as
                // a whole, so it gets a section of its own, as About does.
                SetupRow(
                    icon = Icons.Filled.SettingsBackupRestore,
                    titleRes = R.string.backup_title,
                    subtitleRes = R.string.setup_backup_subtitle,
                    onClick = onOpenBackup,
                )
            }

            item { SectionHeader(R.string.setup_section_about) }
            item {
                // The one row that is not a destination in this app, so it is also the
                // one that resolves its own click rather than taking a lambda from
                // `MainActivity`: there is no `NavController` route to a web page, and
                // threading a twelfth parameter through `HomeScreen` for something that
                // *leaves* the app would say it is navigation when it is not.
                //
                // The policy is hosted rather than bundled because Play requires a
                // public URL for the store listing either way, and two copies of it
                // would eventually disagree.
                PrivacyPolicyRow()
            }
        }
    }
}

/**
 * Opens the hosted privacy policy in whatever the phone uses for the web.
 *
 * `runCatching` because `ACTION_VIEW` throws when nothing on the device handles
 * https — rare, but a phone with no browser is not a phone this screen should crash
 * on. There is nothing useful to say when it happens: the row simply does nothing,
 * which is the same outcome as a browser that opens and fails to load.
 */
@Composable
private fun PrivacyPolicyRow() {
    val context = LocalContext.current
    SetupRow(
        icon = Icons.Filled.PrivacyTip,
        titleRes = R.string.setup_privacy_policy,
        subtitleRes = R.string.setup_privacy_policy_subtitle,
        onClick = {
            val intent = Intent(Intent.ACTION_VIEW, PRIVACY_POLICY_URL.toUri())
            runCatching { context.startActivity(intent) }
        },
    )
}

@Composable
private fun SectionHeader(@StringRes titleRes: Int) {
    Text(
        text = stringResource(titleRes),
        color = EditorColors.textSecondary,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = ROW_INSET, end = ROW_INSET, top = 22.dp, bottom = 8.dp),
    )
}

/**
 * One destination: a glyph, its name, and a line saying what it is for.
 *
 * Metrics follow `WorkflowRow` on the other tab, so the two lists read as one app.
 */
@Composable
private fun SetupRow(
    icon: ImageVector,
    @StringRes titleRes: Int,
    @StringRes subtitleRes: Int,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = ROW_INSET, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = EditorColors.textSecondary,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(16.dp))
        Column {
            Text(
                text = stringResource(titleRes),
                color = EditorColors.textPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = stringResource(subtitleRes),
                color = EditorColors.textSecondary,
                fontSize = 12.sp,
            )
        }
    }
    HorizontalDivider(color = EditorColors.chromeBorder, thickness = 1.dp)
}

/** Matches the workflow list's title row, so the bar does not jump between tabs. */
private val BAR_HEIGHT = 60.dp

/** The horizontal inset every row on both tabs uses. */
private val ROW_INSET = 18.dp

/**
 * Where the privacy policy lives. The same URL is given to Play as the listing's
 * policy link, and is generated from `website/src/pages/privacy.astro`.
 */
private const val PRIVACY_POLICY_URL = "https://easymatic.app/privacy"
