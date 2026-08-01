package com.example.ottomatic.feature.grapheditor

import androidx.compose.ui.geometry.Offset

/**
 * What a finger on a node card can turn out to have meant.
 *
 * Bundled rather than passed one by one because [NodeCard] already carries a long
 * parameter list and these always travel together — they are the outcomes of a
 * single gesture, not independent options.
 *
 * The important pair is [onFinish] and [onCancel]. They used to be the same
 * callback, which is why an accidental pinch left a node nudged: a cancelled drag
 * was written to disk as though the user had let go on purpose.
 */
@Suppress("LongParameterList") // One callback per outcome of a single finger; a holder would only move the list.
data class NodeGestureHandlers(
    /** The finger landed. Records the undo point; must change nothing visible. */
    val onPress: () -> Unit,
    /** Tapped without moving. */
    val onTap: () -> Unit,
    /** Held still past the long-press timeout. */
    val onLongPress: () -> Unit,
    /** Crossed the touch slop; a drag is now in progress. */
    val onDragStart: () -> Unit,
    /** One frame of movement, in graph units. */
    val onDrag: (Offset) -> Unit,
    /** Lifted normally — keep what the drag did. */
    val onFinish: () -> Unit,
    /** Pre-empted or cancelled — undo what the drag did. */
    val onCancel: () -> Unit,
)
