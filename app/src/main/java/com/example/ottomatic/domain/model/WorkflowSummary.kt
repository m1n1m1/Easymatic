package com.example.ottomatic.domain.model

import kotlinx.serialization.Serializable

/**
 * A lightweight projection of a [Workflow] used by the workflow list screen:
 * enough to render a row and to arm/disarm the engine without deserialising the
 * full graph (nodes + edges) for every workflow.
 *
 * [icon] and [accent] are here because a row and a home-screen tile both draw
 * them, and neither should have to decode a graph to find out what colour a macro
 * is. They carry the same defaults [Workflow] gives them, so a file written before
 * either field existed projects onto this without a migration.
 */
@Serializable
data class WorkflowSummary(
    val id: String,
    val name: String,
    val enabled: Boolean = false,
    val icon: MacroIcon = MacroIcon.BOLT,
    val accent: MacroAccent = MacroAccent.SYSTEM,
)
