package io.github.m1n1m1.easymatic.feature.grapheditor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.m1n1m1.easymatic.core.permissions.Permission
import io.github.m1n1m1.easymatic.core.permissions.PermissionRequirement
import io.github.m1n1m1.easymatic.core.permissions.Permissions
import io.github.m1n1m1.easymatic.core.permissions.PrerequisiteType
import io.github.m1n1m1.easymatic.domain.model.NodeKind
import io.github.m1n1m1.easymatic.domain.model.NodeTypeDefinition
import io.github.m1n1m1.easymatic.domain.model.WorkflowNode
import io.github.m1n1m1.easymatic.domain.registry.usesContacts
import androidx.compose.ui.res.stringResource
import io.github.m1n1m1.easymatic.R
import io.github.m1n1m1.easymatic.feature.permissions.rationaleRes
import io.github.m1n1m1.easymatic.feature.permissions.rememberPermissionState
import io.github.m1n1m1.easymatic.feature.permissions.rememberPrerequisiteState

private val CARD_SHAPE = RoundedCornerShape(14.dp)

/**
 * Warns that a node's declared permissions are not granted, and offers to ask
 * for them.
 *
 * Without this a permission-starved trigger fails completely silently: the
 * geofence is never registered, the macro looks armed, and nothing ever
 * happens. The config form is where the user is already thinking about that
 * node, so it is where the gap belongs — not in a startup prompt they dismissed
 * weeks ago.
 *
 * Renders nothing when the node declares no runtime permissions or they are all
 * granted.
 *
 * Takes the placed [node] as well as its type, because one requirement is not a
 * property of the type at all: see [ContactsPermissionNotice].
 */
@Composable
internal fun NodePermissionNotice(definition: NodeTypeDefinition?, node: WorkflowNode) {
    RuntimePermissionNotice(definition)
    ContactsPermissionNotice(node)
    // Prerequisites granted on a Settings page rather than through the runtime
    // dialog. Without this they were invisible: `trigger.notification` looked
    // fully configured while silently never firing, because nothing in the app
    // ever mentioned that notification access had to be switched on.
    definition?.permissionRequirements
        ?.filter { it.type != PrerequisiteType.RUNTIME }
        ?.distinctBy { it.type }
        ?.forEach { SettingsPrerequisiteNotice(it) }
}

/**
 * Asks for contacts access, but **only once the node actually points at a contact**.
 *
 * The one requirement derived from a node's config rather than declared on its type,
 * and the reason is that declaring it statically would be a lie most of the time:
 * `action.call` holding a typed number needs no address book at all, so an amber
 * card on every call node would be permanently on — and a warning that is always
 * there is one people learn to scroll past. See `usesContacts`.
 */
@Composable
private fun ContactsPermissionNotice(node: WorkflowNode) {
    if (!usesContacts(node)) return
    val permissionState = rememberPermissionState(listOf(Permissions.READ_CONTACTS))
    if (permissionState.allGranted) return

    NoticeCard(
        message = stringResource(R.string.grapheditor_this_node_needs_contacts_access),
        actionLabel = stringResource(R.string.grapheditor_grant),
        onAction = permissionState::request,
    )
}

@Composable
private fun SettingsPrerequisiteNotice(requirement: PermissionRequirement) {
    val state = rememberPrerequisiteState(requirement.type)
    if (state.isSatisfied) return
    val explanation = rationaleRes(requirement) ?: return

    NoticeCard(
        message = stringResource(explanation),
        // "Grant" only where a tap really does produce the system dialog, which is the
        // permissions screen's rule and the same one type is the exception to: device
        // admin is activated from a dialog, everything else from a page you have to go
        // and find a switch on.
        actionLabel = stringResource(
            if (requirement.type == PrerequisiteType.DEVICE_ADMIN) {
                R.string.grapheditor_grant
            } else {
                R.string.permissions_open_settings
            },
        ),
        onAction = state::openSettings,
    )
}

@Composable
private fun RuntimePermissionNotice(definition: NodeTypeDefinition?) {
    val required = definition?.permissionRequirements
        ?.filter { it.type == PrerequisiteType.RUNTIME }
        ?.mapNotNull { it.manifestPermission }
        ?.map(::Permission)
        .orEmpty()
    if (required.isEmpty()) return

    val permissionState = rememberPermissionState(required)
    if (permissionState.allGranted) return

    // Three different things happen, so three sentences. The card was written when
    // only triggers could declare a permission; an action does not "fire", it runs
    // and fails, and a value does neither — it is *read*, answers nothing, and lets
    // whatever asked fall back to its own form value. Telling somebody their value
    // node "will fail every time it runs" would send them looking for an error that
    // is never going to appear in the console.
    val consequence = stringResource(
        when (definition?.kind) {
            NodeKind.TRIGGER -> R.string.grapheditor_consequence_never_fires
            NodeKind.VALUE -> R.string.grapheditor_consequence_reads_nothing
            else -> R.string.grapheditor_consequence_fails
        },
    )
    val joiner = stringResource(R.string.grapheditor_permission_joiner)
    NoticeCard(
        message = stringResource(
            R.string.grapheditor_node_needs_permissions,
            consequence,
            permissionState.missing.joinToString(joiner) { it.readableName() },
        ),
        actionLabel = stringResource(R.string.grapheditor_grant),
        onAction = permissionState::request,
    )
}

@Composable
private fun NoticeCard(message: String, actionLabel: String, onAction: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CARD_SHAPE)
            .background(EditorColors.triggerAccent.copy(alpha = 0.08f))
            .border(1.dp, EditorColors.triggerAccent.copy(alpha = 0.35f), CARD_SHAPE)
            .padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.WarningAmber,
                contentDescription = null,
                tint = EditorColors.triggerAccent,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = stringResource(R.string.grapheditor_permission_needed),
                color = EditorColors.triggerAccent,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Text(
            text = message,
            color = EditorColors.textSecondary,
            fontSize = 12.sp,
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}

/**
 * Turns `android.permission.ACCESS_BACKGROUND_LOCATION` into "background
 * location". A raw manifest constant in a sentence tells the user nothing about
 * which switch to look for in the system dialog.
 */
private fun Permission.readableName(): String =
    manifest.substringAfterLast('.').lowercase().replace('_', ' ').removePrefix("access ")
