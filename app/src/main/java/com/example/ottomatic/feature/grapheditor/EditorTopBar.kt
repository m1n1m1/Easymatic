package com.example.ottomatic.feature.grapheditor

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The editor's chrome, in two mutually exclusive modes.
 *
 * With nothing selected the bar is *purely* about the workflow — leave it, name
 * it, arm it, open its console, reach its actions. With something selected it is
 * *purely* about that selection. The two never share the bar, which is what the
 * previous single-mode version could not manage: a name, a node count, a
 * console, a switch, a configure button and a delete button together left the
 * title about seventy dp on a phone, and the subtitle had to choose between
 * saying "6 nodes" and "1 selected".
 *
 * Separating them buys width in both directions. The workflow mode gets its name
 * back plus room for a back arrow it never had; the selection mode has space to
 * say "1 node selected" in full rather than "1 selected".
 *
 * Everything workflow-level that is not one of the four primary controls goes in
 * the overflow menu, so the bar does not have to grow again for the next action.
 *
 * The console and the problems panel used to be icons here, and a third — the
 * variables panel — would have left the title about seventy dp again. All three are
 * now items of [EditorBottomBar], where they cost this bar nothing. Their badges
 * are still permanently visible, on the bar's own items; a warning nobody can see
 * until they open something is not a warning.
 *
 * The [Surface] and its 60.dp height sit *outside* the [Crossfade], so switching
 * modes is a pure fade over a fixed box — the chrome never changes height and
 * the canvas below is never re-measured.
 *
 * Parameters are scalars rather than the [GraphEditorUiState] they come from, so
 * that panning the canvas — which rewrites `transform` on every frame — does not
 * recompose the bar. [EditorBottomBar]'s badges take their counts the same way,
 * and for the same reason.
 */
@Composable
@Suppress("LongParameterList") // Two bars' worth of controls; see the mode split above.
fun EditorTopBar(
    title: String,
    nodeCount: Int,
    /** The selection's description, or null when nothing is selected — which is also the mode. */
    selectionLabel: String?,
    canConfigure: Boolean,
    isMacroEnabled: Boolean,
    onBack: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    onRename: (String) -> Unit,
    onDeleteWorkflow: () -> Unit,
    onClearSelection: () -> Unit,
    onConfigure: () -> Unit,
    onDeleteSelection: () -> Unit,
) {
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }

    Surface(color = EditorColors.chrome) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .height(BAR_HEIGHT)
                // Narrower than the old bar's 18.dp: both modes now open with an
                // IconButton, whose own touch padding supplies the rest. Same
                // values `OverlayTopBar` uses, so the two line up.
                .padding(start = 6.dp, end = 12.dp),
        ) {
            Crossfade(
                targetState = selectionLabel,
                animationSpec = tween(MODE_FADE_MS),
                label = "editorTopBarMode",
                modifier = Modifier.fillMaxSize(),
            ) { label ->
                if (label == null) {
                    WorkflowBar(
                        title = title,
                        nodeCount = nodeCount,
                        isMacroEnabled = isMacroEnabled,
                        onBack = onBack,
                        onToggleEnabled = onToggleEnabled,
                        onRename = { renaming = true },
                        onDelete = { deleting = true },
                    )
                } else {
                    SelectionBar(
                        label = label,
                        canConfigure = canConfigure,
                        onClearSelection = onClearSelection,
                        onConfigure = onConfigure,
                        onDeleteSelection = onDeleteSelection,
                    )
                }
            }
        }
    }

    if (renaming) {
        RenameWorkflowDialog(
            initialName = title,
            onConfirm = {
                onRename(it)
                renaming = false
            },
            onDismiss = { renaming = false },
        )
    }

    if (deleting) {
        DeleteWorkflowDialog(
            name = title,
            onConfirm = {
                deleting = false
                onDeleteWorkflow()
            },
            onDismiss = { deleting = false },
        )
    }
}

/**
 * The editor's chrome height, below the status bar.
 *
 * Not private: [PanelTopBar] *replaces* this bar rather than stacking under it, so
 * the two must be the same height or the canvas would appear to jump when a surface
 * opens.
 */
val BAR_HEIGHT = 60.dp

private const val MODE_FADE_MS = 150

@Composable
private fun WorkflowBar(
    title: String,
    nodeCount: Int,
    isMacroEnabled: Boolean,
    onBack: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxSize(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                tint = EditorColors.textPrimary,
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = EditorColors.textPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = if (nodeCount == 1) "1 node" else "$nodeCount nodes",
                color = EditorColors.textSecondary,
                fontSize = 11.sp,
            )
        }
        // No "Enabled" label beside it any more — it cost more width than the
        // control it described. The switch keeps the name for TalkBack.
        Switch(
            checked = isMacroEnabled,
            onCheckedChange = onToggleEnabled,
            colors = editorSwitchColors(),
            modifier = Modifier.semantics { contentDescription = "Enabled" },
        )
        WorkflowMenu(onRename = onRename, onDelete = onDelete)
    }
}

/**
 * The contextual mode: what is selected, and the two things that can be done to
 * it. Nothing here outlives the selection, so the bar is free to be empty of
 * workflow controls entirely.
 */
@Composable
private fun SelectionBar(
    label: String,
    canConfigure: Boolean,
    onClearSelection: () -> Unit,
    onConfigure: () -> Unit,
    onDeleteSelection: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxSize(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClearSelection) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = "Clear selection",
                tint = EditorColors.textPrimary,
            )
        }
        Text(
            text = label,
            color = EditorColors.nodeSelectedBorder,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(start = 6.dp),
        )
        // Configure edits one node's fields, so it is gated on a lone node
        // rather than on "anything selected" — which used to show the button
        // for an edge and then silently refuse to open the sheet.
        if (canConfigure) {
            IconButton(onClick = onConfigure) {
                Icon(
                    imageVector = Icons.Filled.Settings,
                    contentDescription = "Configure node",
                    tint = EditorColors.textPrimary,
                )
            }
        }
        // Duplicate belongs here, between configure and delete.
        IconButton(onClick = onDeleteSelection) {
            Icon(
                imageVector = Icons.Filled.Delete,
                contentDescription = "Delete selection",
                tint = EditorColors.nodeSelectedBorder,
            )
        }
    }
}

/**
 * Everything workflow-level that is not worth a permanent icon.
 *
 * This is the bar's release valve: duplicate, share/export and per-workflow
 * settings all land here as further [DropdownMenuItem]s, and none of them costs
 * the title a single dp.
 */
@Composable
private fun WorkflowMenu(onRename: () -> Unit, onDelete: () -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { menuOpen = true }) {
            Icon(
                imageVector = Icons.Filled.MoreVert,
                contentDescription = "Workflow actions",
                tint = EditorColors.textPrimary,
            )
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text("Rename…") },
                onClick = {
                    menuOpen = false
                    onRename()
                },
                leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
            )
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("Delete workflow", color = EditorColors.triggerAccent) },
                onClick = {
                    menuOpen = false
                    onDelete()
                },
                leadingIcon = {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = null,
                        tint = EditorColors.triggerAccent,
                    )
                },
            )
        }
    }
}

/** Mirrors the workflow list's rename dialog, so renaming reads the same in both places. */
@Composable
private fun RenameWorkflowDialog(
    initialName: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename workflow", color = EditorColors.textPrimary) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text("Name") },
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name.trim()) },
                enabled = name.isNotBlank(),
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun DeleteWorkflowDialog(
    name: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete workflow", color = EditorColors.textPrimary) },
        text = {
            Text(
                "Delete \"$name\"? Its run log goes with it. This cannot be undone.",
                color = EditorColors.textPrimary,
                fontSize = 14.sp,
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Delete") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

/**
 * [Switch] colours drawn from [EditorColors].
 *
 * The app forces `darkTheme = true, dynamicColor = false` onto the unmodified
 * Android Studio template scheme, so an unstyled switch renders in the
 * template's purple against this palette. It matters more now than it did: with
 * the "Enabled" label dropped, the switch's colour *is* the armed indicator.
 *
 * Shared with the workflow list so a macro's armed state looks the same wherever
 * it is toggled.
 */
@Composable
fun editorSwitchColors(): SwitchColors = SwitchDefaults.colors(
    checkedThumbColor = EditorColors.textPrimary,
    checkedTrackColor = EditorColors.triggerAccent,
    checkedBorderColor = EditorColors.triggerAccent,
    uncheckedThumbColor = EditorColors.textSecondary,
    uncheckedTrackColor = EditorColors.nodeBackground,
    uncheckedBorderColor = EditorColors.nodeBorder,
)
