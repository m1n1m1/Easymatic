package com.example.ottomatic.feature.grapheditor

/**
 * How a node card is picked out from its neighbours.
 *
 * [CANDIDATE] exists so a box selection can show its work: the box is drawn under
 * a thumb, over cards whose edges are hidden by it, so without a live preview of
 * what it has caught the user is committing blind.
 */
enum class NodeHighlight {
    NONE,

    /** Inside the marquee currently being dragged, but not selected yet. */
    CANDIDATE,

    SELECTED,
}
