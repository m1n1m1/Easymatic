package com.example.ottomatic.feature.grapheditor

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
import com.example.ottomatic.core.permissions.Permission
import com.example.ottomatic.core.permissions.PermissionRequirement
import com.example.ottomatic.core.permissions.Permissions
import com.example.ottomatic.core.permissions.PrerequisiteType
import com.example.ottomatic.domain.model.NodeKind
import com.example.ottomatic.domain.model.NodeTypeDefinition
import com.example.ottomatic.domain.model.WorkflowNode
import com.example.ottomatic.domain.registry.usesContacts
import com.example.ottomatic.feature.permissions.rationaleFor
import com.example.ottomatic.feature.permissions.rememberPermissionState
import com.example.ottomatic.feature.permissions.rememberPrerequisiteState

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
        message = "This node needs contacts access to look up the number you chose. " +
            "Without it, it will do nothing when it runs.",
        actionLabel = "Grant",
        onAction = permissionState::request,
    )
}

@Composable
private fun SettingsPrerequisiteNotice(requirement: PermissionRequirement) {
    val state = rememberPrerequisiteState(requirement.type)
    if (state.isSatisfied) return
    val explanation = rationaleFor(requirement) ?: return

    NoticeCard(message = explanation, actionLabel = "Open settings", onAction = state::openSettings)
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
    val consequence = when (definition?.kind) {
        NodeKind.TRIGGER -> "will never fire"
        NodeKind.VALUE -> "will read as nothing"
        else -> "will fail every time it runs"
    }
    NoticeCard(
        message = "This node $consequence until you grant " +
            permissionState.missing.joinToString(" and ") { it.readableName() } + ".",
        actionLabel = "Grant",
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
                text = "Permission needed",
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
