package com.example.ottomatic.feature.grapheditor

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.example.ottomatic.R

/**
 * How a [SelectionSummary] reads — in the editor's contextual top bar, and in the
 * sentence the delete dialog builds around it.
 *
 * Split from the counting for `IssueText`'s reason: the decision is a pure function
 * with its own unit tests, and the wording needs plural resources that only a
 * composition can reach. The `when` is exhaustive, so a new shape of selection cannot
 * be added without deciding what the bar says about it.
 *
 * It says **"2 nodes"** rather than "2 nodes selected". The bar is already the
 * selection mode — it appears only when something is selected, and it opens with a ✕
 * that clears it — so the word restated what the whole bar is for, in the one place
 * on screen where width is scarcest. Dropping it also lets the delete dialog ask
 * "Delete 2 nodes?" off this same string instead of counting the selection a second
 * time in its own words.
 *
 * Nodes and edges are counted separately even though [Selection] unions them,
 * because "3" over a mixed selection reads as three nodes — and the delete that
 * follows is the one action the user cannot undo.
 *
 * [SelectionSummary.Empty] answers empty. The bar shows its workflow mode instead of
 * this, and the dialog cannot open on nothing, so inventing a word for "nothing"
 * would only make that reachable.
 */
@Composable
internal fun selectionText(summary: SelectionSummary): String = when (summary) {
    SelectionSummary.Empty -> ""
    is SelectionSummary.Nodes ->
        pluralStringResource(R.plurals.selection_nodes, summary.count, summary.count)
    is SelectionSummary.Connections ->
        pluralStringResource(R.plurals.selection_connections, summary.count, summary.count)
    is SelectionSummary.Mixed -> stringResource(
        R.string.grapheditor_selection_mixed,
        pluralStringResource(R.plurals.selection_nodes, summary.nodes, summary.nodes),
        pluralStringResource(R.plurals.selection_connections, summary.connections, summary.connections),
    )
}
