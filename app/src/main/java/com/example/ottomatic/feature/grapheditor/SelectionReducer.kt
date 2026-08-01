package com.example.ottomatic.feature.grapheditor

import com.example.ottomatic.core.model.NodeId

/**
 * Pure `GraphEditorUiState -> GraphEditorUiState` steps for selecting things.
 *
 * The ViewModel keeps every gesture method a one-liner over reducers like these,
 * which is not a style preference: the project's only test dependency is junit —
 * no coroutines-test, no Robolectric — so a `GraphEditorViewModel` cannot be
 * instantiated in a unit test at all (`viewModelScope` has no Main dispatcher).
 * Logic that lives only inside the ViewModel is therefore untestable, and the
 * selection rules are exactly the part that must not silently drift.
 */

/**
 * Applies the one invariant every path here shares: **an empty selection means
 * multi-select is off.** A mode for adding to a selection is meaningless without
 * one, and an armed mode with nothing selected would make the next tap toggle
 * with nothing on screen explaining why.
 */
private fun GraphEditorUiState.normalised(): GraphEditorUiState =
    if (selection.isEmpty && interaction.isMultiSelect) {
        copy(interaction = interaction.copy(isMultiSelect = false))
    } else {
        this
    }

internal fun GraphEditorUiState.withSelection(next: Selection): GraphEditorUiState =
    copy(selection = next).normalised()

/**
 * A tap on a node: replaces the selection normally, toggles it in multi-select.
 *
 * Toggling can empty the selection, which leaves multi-select through
 * [normalised] — so tapping the last selected node off is also how you get out of
 * the mode without hunting for empty canvas.
 */
fun GraphEditorUiState.withTappedNode(nodeId: NodeId): GraphEditorUiState =
    if (interaction.isMultiSelect) {
        withSelection(selection.toggleNode(nodeId))
    } else {
        withSelection(Selection.ofNode(nodeId))
    }

/** A tap on an edge, under the same replace-or-toggle rule as a node. */
fun GraphEditorUiState.withTappedConnection(connectionId: String): GraphEditorUiState =
    if (interaction.isMultiSelect) {
        withSelection(selection.toggleConnection(connectionId))
    } else {
        withSelection(Selection.ofConnection(connectionId))
    }

/** A tap on empty canvas: clears everything and leaves multi-select. */
fun GraphEditorUiState.withClearedSelection(): GraphEditorUiState = withSelection(Selection.EMPTY)

/**
 * A long press on a node: turns multi-select on and adds the node.
 *
 * Add rather than toggle — a long press is how the mode is *entered*, and entering
 * it by removing the node you just pressed would be nonsense.
 */
fun GraphEditorUiState.withLongPressedNode(nodeId: NodeId): GraphEditorUiState = copy(
    selection = selection.copy(nodeIds = selection.nodeIds + nodeId),
    interaction = interaction.copy(isMultiSelect = true),
)
