package com.example.ottomatic.domain.model

import kotlinx.serialization.Serializable

/**
 * A node placed on the workflow canvas. Position is stored in graph units (dp).
 *
 * [config] is a flat string-keyed map of *typed* config values: each value is
 * encoded as its string form by the editor and parsed back according to the
 * node's [com.example.ottomatic.domain.registry.NodeConfigSchema] at runtime.
 *
 * [exposedInputs] names the [config] keys that the user has toggled to be
 * exposed as typed DATA input ports on this placed node (see
 * [com.example.ottomatic.domain.registry.effectivePorts]). When an incoming
 * data edge carries an item on such a port, that item's value overrides the
 * static [config] entry for the same key at execution time (see
 * [com.example.ottomatic.engine.WorkflowExecutor]). This replaces the former
 * batch `config` map DATA input port and the `action.make` struct node: a
 * node's individual fields are wired directly from upstream data.
 */
@Serializable
data class WorkflowNode(
    val id: String,
    val typeId: String,
    val name: String,
    val x: Float,
    val y: Float,
    val config: Map<String, String> = emptyMap(),
    val exposedInputs: Set<String> = emptySet(),
)

/**
 * A control-flow edge: "when [fromNodeId] pulses on its exec output port
 * [fromPort], run [toNodeId] on its exec input port [toPort]". Carries no data.
 */
@Serializable
data class ExecConnection(
    val id: String,
    val fromNodeId: String,
    val fromPort: String,
    val toNodeId: String,
    val toPort: String,
)

/**
 * A data edge: a typed [com.example.ottomatic.domain.model.schema.Item]
 * produced on the data output port [fromPort] of [fromNodeId] is fed into the
 * data input port [toPort] of [toNodeId]. Schema compatibility is validated
 * by [com.example.ottomatic.engine.validation.GraphValidator].
 */
@Serializable
data class DataConnection(
    val id: String,
    val fromNodeId: String,
    val fromPort: String,
    val toNodeId: String,
    val toPort: String,
)

/**
 * A complete workflow graph: nodes plus two disjoint edge sets (execution and
 * data). The [schemaVersion] field gates one-time migrations on load.
 */
@Serializable
data class Workflow(
    val id: String = "default",
    val name: String = "My Workflow",
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val nodes: List<WorkflowNode> = emptyList(),
    val execConnections: List<ExecConnection> = emptyList(),
    val dataConnections: List<DataConnection> = emptyList(),
) {
    fun node(id: String): WorkflowNode? = nodes.firstOrNull { it.id == id }

    /** All exec edges leaving [nodeId] from any output port. */
    fun outgoingExec(nodeId: String): List<ExecConnection> =
        execConnections.filter { it.fromNodeId == nodeId }

    /** All exec edges leaving [nodeId] from the given output port. */
    fun outgoingExec(nodeId: String, port: String): List<ExecConnection> =
        execConnections.filter { it.fromNodeId == nodeId && it.fromPort == port }

    /** All exec edges entering [nodeId] on any input port. */
    fun incomingExec(nodeId: String): List<ExecConnection> =
        execConnections.filter { it.toNodeId == nodeId }

    /** All data edges entering [nodeId] on any input port. */
    fun incomingData(nodeId: String): List<DataConnection> =
        dataConnections.filter { it.toNodeId == nodeId }

    /** All data edges entering [nodeId] on the given input port. */
    fun incomingData(nodeId: String, port: String): List<DataConnection> =
        dataConnections.filter { it.toNodeId == nodeId && it.toPort == port }

    companion object {
        const val CURRENT_SCHEMA_VERSION = 2
    }
}
