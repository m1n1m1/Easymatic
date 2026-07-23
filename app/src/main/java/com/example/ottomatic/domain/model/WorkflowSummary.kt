package com.example.ottomatic.domain.model

import kotlinx.serialization.Serializable

/**
 * A lightweight projection of a [Workflow] used by the workflow list screen:
 * enough to render a row and to arm/disarm the engine without deserialising the
 * full graph (nodes + edges) for every workflow.
 */
@Serializable
data class WorkflowSummary(
    val id: String,
    val name: String,
    val enabled: Boolean = false,
)
