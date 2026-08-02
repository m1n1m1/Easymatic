package com.example.ottomatic.feature.workflowlist

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.ottomatic.feature.grapheditor.EditorColors
import com.example.ottomatic.feature.grapheditor.editorSwitchColors

@Composable
fun WorkflowListScreen(
    viewModel: WorkflowListViewModel,
    onOpenWorkflow: (String) -> Unit,
    onOpenGeofences: () -> Unit,
) {
    val state by viewModel.uiState.collectAsState()

    // Refresh the list whenever the screen re-enters the foreground (e.g. when
    // returning from the graph editor, whose edits change names/enabled state).
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    var renaming by remember { mutableStateOf<com.example.ottomatic.domain.model.WorkflowSummary?>(null) }
    var deleting by remember { mutableStateOf<com.example.ottomatic.domain.model.WorkflowSummary?>(null) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(EditorColors.canvasBackground),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Surface(color = EditorColors.chrome) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .height(60.dp)
                        .padding(horizontal = 18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Workflows",
                        color = EditorColors.textPrimary,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    // Places are edited independently of any macro, so the
                    // library needs a way in that does not start with "open a
                    // workflow that happens to use one".
                    IconButton(onClick = onOpenGeofences) {
                        Icon(
                            imageVector = Icons.Filled.Place,
                            contentDescription = "Geofences",
                            tint = EditorColors.textPrimary,
                        )
                    }
                }
            }

            if (state.workflows.isEmpty() && !state.isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "No workflows yet.\nTap + to create one.",
                        color = EditorColors.textSecondary,
                        fontSize = 14.sp,
                    )
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(state.workflows, key = { it.id }) { summary ->
                        WorkflowRow(
                            summary = summary,
                            errors = state.errors[summary.id] ?: 0,
                            onOpen = { onOpenWorkflow(summary.id) },
                            onToggleEnabled = { viewModel.setEnabled(summary.id, it) },
                            onRename = { renaming = summary },
                            onDelete = { deleting = summary },
                        )
                        HorizontalDivider(color = EditorColors.chromeBorder, thickness = 1.dp)
                    }
                }
            }
        }

        FloatingActionButton(
            onClick = { viewModel.create(onOpenWorkflow) },
            containerColor = EditorColors.actionAccent,
            contentColor = EditorColors.textPrimary,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(end = 18.dp, bottom = 18.dp),
        ) {
            Icon(Icons.Filled.Add, contentDescription = "Add workflow")
        }
    }

    renaming?.let { target ->
        RenameDialog(
            initialName = target.name,
            onConfirm = { newName ->
                viewModel.rename(target.id, newName)
                renaming = null
            },
            onDismiss = { renaming = null },
        )
    }

    deleting?.let { target ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete workflow", color = EditorColors.textPrimary) },
            text = {
                Text(
                    "Delete \"${target.name}\"? This cannot be undone.",
                    color = EditorColors.textPrimary,
                    fontSize = 14.sp,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(target.id)
                    deleting = null
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text("Cancel") }
            },
        )
    }
}

/**
 * The line under a workflow's name.
 *
 * A broken graph outranks the armed flag because it is the more surprising fact:
 * "Armed" beside a macro that stops at its first bad wire is the reading this
 * screen used to give, and the one the user is least likely to question.
 */
private fun statusText(enabled: Boolean, errors: Int): String = when {
    errors > 0 && enabled -> "Armed · $errors ${if (errors == 1) "problem" else "problems"}"
    errors > 0 -> "$errors ${if (errors == 1) "problem" else "problems"}"
    enabled -> "Armed"
    else -> "Off"
}

@Composable
private fun WorkflowRow(
    summary: com.example.ottomatic.domain.model.WorkflowSummary,
    errors: Int,
    onOpen: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = summary.name,
                color = EditorColors.textPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
            )
            // "Armed" on its own is a half-truth for a graph that cannot run all
            // the way through, so the problem count replaces it rather than
            // sitting beside it.
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (errors > 0) {
                    Icon(
                        imageVector = Icons.Filled.ErrorOutline,
                        contentDescription = null,
                        tint = EditorColors.errorAccent,
                        modifier = Modifier
                            .size(13.dp)
                            .padding(end = 1.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    text = statusText(summary.enabled, errors),
                    color = when {
                        errors > 0 -> EditorColors.errorAccent
                        summary.enabled -> EditorColors.triggerAccent
                        else -> EditorColors.textSecondary
                    },
                    fontSize = 12.sp,
                )
            }
        }
        Switch(
            checked = summary.enabled,
            onCheckedChange = onToggleEnabled,
            colors = editorSwitchColors(),
        )
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(
                    Icons.Filled.MoreVert,
                    contentDescription = "More",
                    tint = EditorColors.textSecondary,
                )
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("Rename") },
                    onClick = {
                        menuOpen = false
                        onRename()
                    },
                    leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                )
                DropdownMenuItem(
                    text = { Text("Delete") },
                    onClick = {
                        menuOpen = false
                        onDelete()
                    },
                    leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                )
            }
        }
    }
}

@Composable
private fun RenameDialog(
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
            TextButton(onClick = { if (name.isNotBlank()) onConfirm(name.trim()) }) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
