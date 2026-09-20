package io.github.m1n1m1.easymatic.feature.workflowlist

import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import io.github.m1n1m1.easymatic.R
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExpandedFullScreenSearchBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SearchBarColors
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.SearchBarState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldColors
import androidx.compose.material3.rememberSearchBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import io.github.m1n1m1.easymatic.domain.model.WorkflowSummary
import io.github.m1n1m1.easymatic.feature.grapheditor.EditorColors
import io.github.m1n1m1.easymatic.feature.grapheditor.editorSwitchColors
import io.github.m1n1m1.easymatic.feature.macro.EditMacroDialog
import io.github.m1n1m1.easymatic.feature.macro.MacroActionsMenu
import io.github.m1n1m1.easymatic.feature.macro.rememberMacroPinner
import io.github.m1n1m1.easymatic.feature.macro.editorTextButtonColors
import io.github.m1n1m1.easymatic.feature.macro.MacroIconChip
import io.github.m1n1m1.easymatic.feature.widget.ManualTriggerRef
import kotlinx.coroutines.launch

/**
 * The macro list, and the search over it.
 *
 * This screen used to be the app's navigation hub as well: its title row carried a
 * Global variables icon, a Permissions icon and an eight-item overflow menu, and the
 * file said in a comment that three targets was the ceiling. Those ten destinations
 * are rows on the Setup tab now
 * ([io.github.m1n1m1.easymatic.feature.setup.SetupScreen]), and the row they vacated is
 * a regular app bar — the app's icon, its name, and a search action — so this screen is
 * back to being about macros. The search itself is Material's full-screen search bar,
 * and it grows out of that action rather than out of a collapsed pill of its own: the
 * action's coordinates are handed to the [SearchBarState] as its collapsed bounds, which
 * is the one thing the expansion animation reads.
 *
 * Collapsing the search keeps the query, so picking a result and coming back lands on the
 * list still narrowed to it — but with no pill on the screen the query would then be a
 * filter nobody can see. [ActiveFilterChip] is what keeps it visible: the query under the
 * bar, with a ✕ to drop it and a tap to reopen the search.
 *
 * The query lives here rather than in the ViewModel. Switching tabs drops this
 * composition and clears it, which is the right default: a filter you cannot see is
 * worse than one you have to retype, and nothing else on the screen depends on it.
 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun WorkflowListScreen(
    viewModel: WorkflowListViewModel,
    onOpenHelp: () -> Unit,
    onOpenWorkflow: (String) -> Unit,
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
    // Draws its own two dialogs — which button to pin, and the launcher that refuses.
    val pinner = rememberMacroPinner(viewModel::pin)

    val transfer = rememberMacroTransfer(viewModel)

    val scope = rememberCoroutineScope()
    val searchBarState = rememberSearchBarState()
    val textFieldState = rememberTextFieldState()
    val barColors = searchBarColors()
    val query = textFieldState.text.toString().trim()
    val matches = if (query.isEmpty()) {
        state.workflows
    } else {
        state.workflows.filter { it.name.contains(query, ignoreCase = true) }
    }

    // One input field, handed to both the collapsed bar and the expanded surface.
    // That is what lets the component move the field between the two rather than
    // cross-fade two of its own — so it is deliberately a single lambda, not two
    // call sites that happen to look alike.
    val inputField: @Composable () -> Unit = {
        SearchInputField(
            searchBarState = searchBarState,
            textFieldState = textFieldState,
            colors = barColors.inputFieldColors,
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(EditorColors.canvasBackground),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Supplies its own window insets, which already include the status bar —
            // so this screen no longer pads for it itself.
            WorkflowListTopBar(searchBarState = searchBarState, onOpenHelp = onOpenHelp)
            if (query.isNotEmpty()) {
                ActiveFilterChip(
                    query = query,
                    onClear = { textFieldState.clearText() },
                    onOpenSearch = { scope.launch { searchBarState.animateToExpanded() } },
                )
            }

            // Three states, not two: a phone with no macros at all and a search that
            // matched none of them are different facts, and one message for both
            // would tell a user with twenty macros that they have none.
            if (state.workflows.isEmpty()) {
                if (!state.isLoading) EmptyMessage(R.string.workflowlist_no_workflows_yet_ntap_to)
            } else if (matches.isEmpty()) {
                EmptyMessage(R.string.workflowlist_no_matches)
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(matches, key = { it.id }) { summary ->
                        val triggers = state.triggers[summary.id].orEmpty()
                        // Keyed rows plus animateItem: a deleted macro collapses out,
                        // a duplicated one grows in beside its original, and a filter
                        // typed in the search bar reflows what is left rather than
                        // snapping it.
                        Column(modifier = Modifier.animateItem()) {
                            WorkflowRow(
                                summary = summary,
                                errors = state.errors[summary.id] ?: 0,
                                onOpen = { onOpenWorkflow(summary.id) },
                                onToggleEnabled = { viewModel.setEnabled(summary.id, it) },
                                onEdit = { editing = summary },
                                onDelete = { deleting = summary },
                                onDuplicate = { viewModel.duplicate(summary.id) },
                                onExport = { transfer.export(summary.id, summary.name) },
                                onShare = { viewModel.share(summary.id, summary.name) },
                                manualTriggers = triggers,
                                onPin = { pinner.pin(triggers) },
                            )
                            HorizontalDivider(color = EditorColors.chromeBorder, thickness = 1.dp)
                        }
                    }
                }
            }
        }

        // No navigationBarsPadding(): the navigation bar below this screen pads
        // itself for the gesture handle, and a second helping here would float the
        // button an inset above it.
        FloatingActionButton(
            onClick = { viewModel.create(onOpenWorkflow) },
            containerColor = EditorColors.actionAccent,
            contentColor = EditorColors.textPrimary,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 18.dp, bottom = 18.dp),
        ) {
            Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.workflowlist_add_workflow))
        }

        // A small secondary button above the Add FAB rather than an item inside it.
        //
        // Importing and creating are both "a new macro appears", so they belong in the
        // same corner — but turning the Add FAB into a menu would put a tap in front of
        // the overwhelmingly common action to make room for a rare one. A smaller,
        // quieter button beside it keeps creating a single tap and still puts importing
        // where somebody would look for it.
        SmallFloatingActionButton(
            onClick = transfer::import,
            containerColor = EditorColors.chrome,
            contentColor = EditorColors.textSecondary,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 24.dp, bottom = 90.dp),
        ) {
            Icon(
                Icons.Filled.FileDownload,
                contentDescription = stringResource(R.string.macro_transfer_import),
            )
        }
    }

    MacroSearchResults(
        searchBarState = searchBarState,
        inputField = inputField,
        colors = barColors,
        matches = matches,
        onOpenWorkflow = onOpenWorkflow,
    )

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

    state.transfer?.let { message ->
        TransferDialog(message = message, onDismiss = viewModel::dismissTransfer)
    }

    deleting?.let { target ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.workflowlist_delete_workflow), color = EditorColors.textPrimary) },
            text = {
                Text(
                    stringResource(R.string.workflowlist_delete_confirm, target.name),
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
                ) { Text(stringResource(R.string.workflowlist_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }, colors = editorTextButtonColors()) { Text(
                    stringResource(R.string.workflowlist_cancel)) }
            },
        )
    }
}

/**
 * The expanded half of the same bar.
 *
 * Its rows are names and nothing else: this is a way to *find* a macro, so the armed
 * switch and the overflow menu `WorkflowRow` carries would be three decisions offered
 * to somebody who has asked one question — and a mis-tap there disarms a macro
 * instead of opening it. Picking one collapses the bar and opens it, and the query
 * survives that, so the list underneath is still filtered when you come back.
 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun MacroSearchResults(
    searchBarState: SearchBarState,
    inputField: @Composable () -> Unit,
    colors: SearchBarColors,
    matches: List<WorkflowSummary>,
    onOpenWorkflow: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    ExpandedFullScreenSearchBar(
        state = searchBarState,
        inputField = inputField,
        colors = colors,
    ) {
        if (matches.isEmpty()) {
            EmptyMessage(R.string.workflowlist_no_matches)
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(matches, key = { it.id }) { summary ->
                    MacroSuggestionRow(
                        summary = summary,
                        onClick = {
                            scope.launch { searchBarState.animateToCollapsed() }
                            onOpenWorkflow(summary.id)
                        },
                    )
                }
            }
        }
    }
}

/**
 * The field itself, shared by the collapsed bar and the expanded surface.
 *
 * It reads its own text rather than being handed a query, because the only thing it
 * needs the text for is whether to draw the clear button — and reading it here keeps
 * that recomposition off the screen around it.
 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun SearchInputField(
    searchBarState: SearchBarState,
    textFieldState: TextFieldState,
    colors: TextFieldColors,
) {
    val scope = rememberCoroutineScope()
    SearchBarDefaults.InputField(
        textFieldState = textFieldState,
        searchBarState = searchBarState,
        // There is nothing to submit: the list is already filtered as you type, so
        // the keyboard's search key just puts the keyboard away.
        onSearch = { scope.launch { searchBarState.animateToCollapsed() } },
        placeholder = { Text(stringResource(R.string.workflowlist_search_placeholder)) },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        trailingIcon = {
            if (textFieldState.text.isNotEmpty()) {
                IconButton(onClick = { textFieldState.clearText() }) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = stringResource(R.string.workflowlist_clear_search),
                    )
                }
            }
        },
        colors = colors,
    )
}

/**
 * The search bar's palette.
 *
 * Only the colours are overridden, as everywhere else in this app: the app's palette
 * is fixed dark and independent of `MaterialTheme`, so a bar taking
 * `MaterialTheme.colorScheme` would render light under it. Which [EditorColors] token
 * fills which slot follows `PaletteSearchField` in the node palette, so the two
 * search fields in the app look like the same control.
 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun searchBarColors(): SearchBarColors = SearchBarDefaults.colors(
    // A step lighter than the chrome, so the pill reads as a field on the canvas
    // rather than as another bar across the top of it.
    containerColor = EditorColors.nodeBackground,
    dividerColor = EditorColors.chromeBorder,
    inputFieldColors = SearchBarDefaults.inputFieldColors(
        focusedTextColor = EditorColors.textPrimary,
        unfocusedTextColor = EditorColors.textPrimary,
        cursorColor = EditorColors.portSnap,
        focusedLeadingIconColor = EditorColors.textSecondary,
        unfocusedLeadingIconColor = EditorColors.textSecondary,
        focusedTrailingIconColor = EditorColors.textSecondary,
        unfocusedTrailingIconColor = EditorColors.textSecondary,
        focusedPlaceholderColor = EditorColors.textSecondary,
        unfocusedPlaceholderColor = EditorColors.textSecondary,
    ),
)

/** The centred line shown when the list has nothing in it, whatever the reason. */
@Composable
private fun EmptyMessage(@StringRes textRes: Int) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = stringResource(textRes),
            color = EditorColors.textSecondary,
            fontSize = 14.sp,
        )
    }
}

/**
 * A macro as a search result: the chip it wears everywhere else, and its name.
 *
 * Deliberately not [WorkflowRow]. That row carries an armed switch and an overflow
 * menu, which are the right things to offer somebody browsing their macros and the
 * wrong ones to put under a cursor in a search field — a mis-tap there disarms a
 * macro instead of opening it.
 */
@Composable
private fun MacroSuggestionRow(summary: WorkflowSummary, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MacroIconChip(icon = summary.icon, accent = summary.accent)
        Spacer(Modifier.width(14.dp))
        Text(
            text = summary.name,
            color = EditorColors.textPrimary,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
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
@Composable
private fun statusText(enabled: Boolean, errors: Int): String {
    if (errors == 0) {
        return stringResource(
            if (enabled) R.string.workflowlist_armed else R.string.workflowlist_off,
        )
    }
    val problems = pluralStringResource(R.plurals.widget_problem_count, errors, errors)
    return if (enabled) {
        stringResource(R.string.workflowlist_armed_with_problems, problems)
    } else {
        problems
    }
}

@Composable
private fun WorkflowRow(
    summary: WorkflowSummary,
    errors: Int,
    onOpen: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onDuplicate: () -> Unit,
    onExport: () -> Unit,
    onShare: () -> Unit,
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
                    contentDescription = stringResource(R.string.workflowlist_more),
                    tint = EditorColors.textSecondary,
                )
            }
            // The same menu the editor's own bar opens — see [MacroActionsMenu].
            MacroActionsMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
                canPin = manualTriggers.isNotEmpty(),
                onEdit = onEdit,
                onPin = onPin,
                onDuplicate = onDuplicate,
                onExport = onExport,
                onShare = onShare,
                onDelete = onDelete,
            )
        }
    }
}

