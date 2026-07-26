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
import com.example.ottomatic.core.permissions.PrerequisiteType
import com.example.ottomatic.domain.model.NodeTypeDefinition
import com.example.ottomatic.feature.permissions.rememberPermissionState

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
 */
@Composable
internal fun NodePermissionNotice(definition: NodeTypeDefinition?) {
    val required = definition?.permissionRequirements
        ?.filter { it.type == PrerequisiteType.RUNTIME }
        ?.mapNotNull { it.manifestPermission }
        ?.map(::Permission)
        .orEmpty()
    if (required.isEmpty()) return

    val permissionState = rememberPermissionState(required)
    if (permissionState.allGranted) return

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
            text = "This node will never fire until you grant " +
                permissionState.missing.joinToString(" and ") { it.readableName() } + ".",
            color = EditorColors.textSecondary,
            fontSize = 12.sp,
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = permissionState::request) { Text("Grant") }
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
