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
import androidx.compose.material.icons.filled.AddToHomeScreen
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Mail
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
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
import com.example.ottomatic.domain.model.WorkflowSummary
import com.example.ottomatic.feature.grapheditor.EditorColors
import com.example.ottomatic.feature.grapheditor.editorSwitchColors
import com.example.ottomatic.feature.macro.EditMacroDialog
import com.example.ottomatic.feature.macro.editorTextButtonColors
import com.example.ottomatic.feature.macro.MacroIconChip
import com.example.ottomatic.feature.widget.ManualTriggerRef

@Composable
fun WorkflowListScreen(
    viewModel: WorkflowListViewModel,
    onOpenWorkflow: (String) -> Unit,
    onOpenGeofences: () -> Unit,
    onOpenNfcTags: () -> Unit,
    onOpenMailAccounts: () -> Unit,
    onOpenSmartHome: () -> Unit,
    onOpenAi: () -> Unit,
    onOpenVariables: () -> Unit,
    onOpenPermissions: () -> Unit,
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

    var editing by remember { mutableStateOf<WorkflowSummary?>(null) }
    var deleting by remember { mutableStateOf<WorkflowSummary?>(null) }
    var pinning by remember { mutableStateOf<List<ManualTriggerRef>?>(null) }
    var pinRefused by remember { mutableStateOf(false) }

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
                    // The libraries are edited independently of any macro, so each
                    // needs a way in that does not start with "open a workflow that
                    // happens to use one". A workflow's *own* variables have no
                    // button here: they live in that workflow's dock, beside the
                    // graph that uses them.
                    //
                    // This bar used to carry four icons and a comment saying four
                    // was the ceiling. A fourth library arrived, and adding an
                    // overflow *beside* the four would have made it five 48 dp
                    // targets — about two thirds of the bar — which is the thing
                    // that comment was guarding against. So the overflow absorbs
                    // icons rather than joining them.
                    //
                    // Variables stays out because every macro touches it, and
                    // Permissions stays out because it is not a library at all but
                    // the standing statement about the phone that the Problems
                    // panel sends people to. What moved is used by one or two node
                    // types each, and one of them is meaningless on a phone with no
                    // NFC chip. A fifth library now costs one DropdownMenuItem.
                    IconButton(onClick = onOpenVariables) {
                        Icon(
                            imageVector = Icons.Filled.Tag,
                            contentDescription = "Global variables",
                            tint = EditorColors.textPrimary,
                        )
                    }
                    IconButton(onClick = onOpenPermissions) {
                        Icon(
                            imageVector = Icons.Filled.Shield,
                            contentDescription = "Permissions",
                            tint = EditorColors.textPrimary,
                        )
                    }
                    LibraryMenu(
                        onOpenGeofences = onOpenGeofences,
                        onOpenNfcTags = onOpenNfcTags,
                        onOpenMailAccounts = onOpenMailAccounts,
                        onOpenSmartHome = onOpenSmartHome,
                        onOpenAi = onOpenAi,
                    )
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
                            onEdit = { editing = summary },
                            onDelete = { deleting = summary },
                            manualTriggers = state.triggers[summary.id].orEmpty(),
                            onPin = {
                                val triggers = state.triggers[summary.id].orEmpty()
                                // One trigger is not a choice, so it is not a
                                // dialog: placing goes straight to the launcher's
                                // own confirmation, which is the only prompt that
                                // decision actually needs.
                                if (triggers.size == 1) {
                                    if (!viewModel.pin(triggers.first())) pinRefused = true
                                } else {
                                    pinning = triggers
                                }
                            },
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

    editing?.let { target ->
        EditMacroDialog(
            initialName = target.name,
            initialIcon = target.icon,
            initialAccent = target.accent,
            onConfirm = { name, icon, accent ->
                viewModel.updateMacro(target.id, name, icon, accent)
                editing = null
            },
            onDismiss = { editing = null },
        )
    }

    // Only ever shown for a macro with more than one manual trigger: the question
    // is which button to pin, and a macro with one has no such question.
    pinning?.let { triggers ->
        AlertDialog(
            onDismissRequest = { pinning = null },
            containerColor = EditorColors.chrome,
            title = { Text("Which trigger?", color = EditorColors.textPrimary) },
            text = {
                Column {
                    triggers.forEach { trigger ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    pinning = null
                                    if (!viewModel.pin(trigger)) pinRefused = true
                                }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            MacroIconChip(icon = trigger.icon, accent = trigger.accent)
                            Spacer(Modifier.width(12.dp))
                            Text(trigger.label, color = EditorColors.textPrimary, fontSize = 15.sp)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { pinning = null }, colors = editorTextButtonColors()) {
                    Text("Cancel")
                }
            },
        )
    }

    if (pinRefused) {
        AlertDialog(
            onDismissRequest = { pinRefused = false },
            containerColor = EditorColors.chrome,
            title = { Text("Can't add it from here", color = EditorColors.textPrimary) },
            text = {
                Text(
                    "This launcher doesn't let an app place a widget for you. " +
                        "Add the Run tile yourself from your home screen's widget list — " +
                        "it will ask which trigger it is for.",
                    color = EditorColors.textPrimary,
                    fontSize = 14.sp,
                )
            },
            confirmButton = {
                TextButton(onClick = { pinRefused = false }, colors = editorTextButtonColors()) {
                    Text("OK")
                }
            },
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
                TextButton(
                    onClick = {
                        viewModel.delete(target.id)
                        deleting = null
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = EditorColors.errorAccent),
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }, colors = editorTextButtonColors()) { Text("Cancel") }
            },
        )
    }
}

/**
 * The libraries that no longer have room for an icon of their own.
 *
 * Each item keeps the glyph it used to wear in the bar, now as a leading icon
 * beside a name — which is a small gain rather than a consolation: a place pin and
 * an NFC mark had to be recognised, where "Geofences" and "NFC tags" are read.
 */
@Composable
private fun LibraryMenu(
    onOpenGeofences: () -> Unit,
    onOpenNfcTags: () -> Unit,
    onOpenMailAccounts: () -> Unit,
    onOpenSmartHome: () -> Unit,
    onOpenAi: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(
                imageVector = Icons.Filled.MoreVert,
                contentDescription = "Libraries",
                tint = EditorColors.textPrimary,
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("Geofences") },
                leadingIcon = { Icon(Icons.Filled.Place, contentDescription = null) },
                onClick = {
                    expanded = false
                    onOpenGeofences()
                },
            )
            DropdownMenuItem(
                text = { Text("NFC tags") },
                leadingIcon = { Icon(Icons.Filled.Nfc, contentDescription = null) },
                onClick = {
                    expanded = false
                    onOpenNfcTags()
                },
            )
            DropdownMenuItem(
                text = { Text("Mail accounts") },
                leadingIcon = { Icon(Icons.Filled.Mail, contentDescription = null) },
                onClick = {
                    expanded = false
                    onOpenMailAccounts()
                },
            )
            DropdownMenuItem(
                text = { Text("Smart home") },
                leadingIcon = { Icon(Icons.Filled.Lightbulb, contentDescription = null) },
                onClick = {
                    expanded = false
                    onOpenSmartHome()
                },
            )
            // Last, and the one entry here that is not a library of many things:
            // it is one key for the phone. It belongs in this menu anyway, because
            // what the menu really collects is "the things a macro needs that are
            // set up once and shared by all of them".
            DropdownMenuItem(
                text = { Text("AI") },
                leadingIcon = { Icon(Icons.Filled.Psychology, contentDescription = null) },
                onClick = {
                    expanded = false
                    onOpenAi()
                },
            )
        }
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
    summary: WorkflowSummary,
    errors: Int,
    onOpen: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    manualTriggers: List<ManualTriggerRef>,
    onPin: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The same chip the home-screen tile and the pinned shortcut draw. A list
        // of names alone gave the user nothing to aim at, and the icon they pick
        // here is the one they will be looking for on the home screen.
        MacroIconChip(icon = summary.icon, accent = summary.accent)
        Spacer(Modifier.width(14.dp))
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
                    text = { Text("Edit") },
                    onClick = {
                        menuOpen = false
                        onEdit()
                    },
                    leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                )
                // Only offered when there is something to pin. A macro with no
                // manual trigger has no button to put on a home screen, and an
                // item that explains that after being tapped is worse than an
                // item that is not there.
                if (manualTriggers.isNotEmpty()) {
                    DropdownMenuItem(
                        text = { Text("Add to home screen") },
                        onClick = {
                            menuOpen = false
                            onPin()
                        },
                        leadingIcon = { Icon(Icons.Filled.AddToHomeScreen, contentDescription = null) },
                    )
                }
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

