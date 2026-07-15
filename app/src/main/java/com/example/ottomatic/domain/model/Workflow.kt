package com.example.ottomatic.domain.model

import kotlinx.serialization.Serializable

/**
 * A node placed on the workflow canvas. Position is stored in graph units (dp).
 */
@Serializable
data class WorkflowNode(
    val id: String,
    val typeId: String,
    val name: String,
    val x: Float,
    val y: Float,
)

/**
 * A directed connection from an output port of one node to an input port of another.
 */
@Serializable
data class Connection(
    val id: String,
    val fromNodeId: String,
    val fromPortIndex: Int,
    val toNodeId: String,
    val toPortIndex: Int,
)

/**
 * A complete workflow graph.
 */
@Serializable
data class Workflow(
    val id: String = "default",
    val name: String = "My Workflow",
    val nodes: List<WorkflowNode> = emptyList(),
    val connections: List<Connection> = emptyList(),
) {
    fun node(id: String): WorkflowNode? = nodes.firstOrNull { it.id == id }
}
