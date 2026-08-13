package com.example.ottomatic.feature.grapheditor

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.example.ottomatic.R

/**
 * How a [SelectionSummary] reads in the editor's contextual top bar.
 *
 * Split from the counting for `IssueText`'s reason: the decision is a pure function
 * with its own unit tests, and the wording needs plural resources that only a
 * composition can reach. The `when` is exhaustive, so a new shape of selection cannot
 * be added without deciding what the bar says about it.
 */
@Composable
internal fun selectionText(summary: SelectionSummary): String = when (summary) {
    SelectionSummary.Empty -> stringResource(R.string.grapheditor_nothing_selected)
    is SelectionSummary.Nodes ->
        pluralStringResource(R.plurals.selection_nodes_selected, summary.count, summary.count)
    is SelectionSummary.Connections ->
        pluralStringResource(R.plurals.selection_connections_selected, summary.count, summary.count)
    // Counted by kind rather than as one total: the delete that follows is the one
    // action the user cannot undo, so the bar says exactly what is about to go.
    is SelectionSummary.Mixed -> stringResource(
        R.string.grapheditor_selection_mixed,
        pluralStringResource(R.plurals.selection_nodes, summary.nodes, summary.nodes),
        pluralStringResource(R.plurals.selection_connections, summary.connections, summary.connections),
    )
}
