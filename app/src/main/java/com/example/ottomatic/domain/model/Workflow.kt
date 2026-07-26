package com.example.ottomatic.domain.model

import com.example.ottomatic.core.model.ConfigKey
import com.example.ottomatic.core.model.NodeId
import com.example.ottomatic.core.model.NodeTypeId
import com.example.ottomatic.core.model.PortName
import kotlinx.serialization.Serializable

/**
 * A comparison attached to a [WorkflowNode], gating whether that node runs.
 *
 * This is the *attached* placement of the graph's single comparison: the same
 * thing `action.if` does on the canvas lives here instead as a settings blob, so
 * no edge has to be drawn for the common "only when X" case. [config] is a
 * [com.example.ottomatic.engine.action.CompareConfig] in flat map form —
 * identical in shape to [WorkflowNode.config], because both are decoded by the
 * same [com.example.ottomatic.domain.registry.NodeSchema].
 *
 * There is no `typeId`: every gate is a comparison. What varies is the *source*
 * it inspects, which its config names — a value node read on demand, or one of
 * the host's own data inputs (see [com.example.ottomatic.engine.ValueSource]).
 *
 * [negated] inverts the result, so one comparison covers "Wi-Fi is on" and "Wi-Fi
 * is not on" without a second declaration.
 */
@Serializable
data class AttachedCondition(
    val config: Map<ConfigKey, String> = emptyMap(),
    val negated: Boolean = false,
)

/** How a node's [WorkflowNode.conditions] combine into a single verdict. */
@Serializable
enum class ConditionLogic {
    AND,
    OR,
}

/**
 * A node placed on the workflow canvas. Position is stored in graph units (dp).
 *
 * [config] is a flat map of *typed* config values keyed by [ConfigKey]: each value
 * is encoded as its string form by the editor and parsed back according to the
 * node's config class at runtime (see
 * [com.example.ottomatic.domain.registry.NodeSchema]).
 *
 * Values that can be wired from upstream data are declared as `@Wired` properties
 * on that same config class, which makes them DATA input ports as well — so a
 * config key and its port name are the same string by construction.
 * [visibleDataInputs] controls which DATA input handles are shown in the editor.
 *
 * [conditions] gate this node: when they do not pass, the node is skipped and its
 * exec output never pulses, so the whole branch below it stops. They are
 * evaluated against this node's own already-collected data inputs, which is why
 * they need no ports of their own.
 */
@Serializable
data class WorkflowNode(
    val id: NodeId,
    val typeId: NodeTypeId,
    val name: String,
    val x: Float,
    val y: Float,
    val config: Map<ConfigKey, String> = emptyMap(),
    val visibleDataInputs: Set<PortName> = emptySet(),
    val conditions: List<AttachedCondition> = emptyList(),
    val conditionLogic: ConditionLogic = ConditionLogic.AND,
)

/**
 * A control-flow edge: "when [fromNodeId] pulses on its exec output port
 * [fromPort], run [toNodeId] on its exec input port [toPort]". Carries no data.
 */
@Serializable
data class ExecConnection(
    val id: String,
    val fromNodeId: NodeId,
    val fromPort: PortName,
    val toNodeId: NodeId,
    val toPort: PortName,
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
    val fromNodeId: NodeId,
    val fromPort: PortName,
    val toNodeId: NodeId,
    val toPort: PortName,
)

/**
 * A complete workflow graph: nodes plus two disjoint edge sets (execution and
 * data). The [schemaVersion] field gates one-time migrations on load.
 *
 * [enabled] is the user's persisted intent to keep this macro armed in the
 * background (driven by [com.example.ottomatic.engine.service.MacroEngineService]).
 * It is orthogonal to the in-editor "Run" preview, which executes the workflow
 * once on the ViewModel scope without persisting this flag.
 */
@Serializable
data class Workflow(
    val id: String = "default",
    val name: String = "My Workflow",
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val nodes: List<WorkflowNode> = emptyList(),
    val execConnections: List<ExecConnection> = emptyList(),
    val dataConnections: List<DataConnection> = emptyList(),
    val enabled: Boolean = false,
) {
    fun node(id: NodeId): WorkflowNode? = nodes.firstOrNull { it.id == id }

    /** All exec edges leaving [nodeId] from any output port. */
    fun outgoingExec(nodeId: NodeId): List<ExecConnection> =
        execConnections.filter { it.fromNodeId == nodeId }

    /** All exec edges leaving [nodeId] from the given output port. */
    fun outgoingExec(nodeId: NodeId, port: PortName): List<ExecConnection> =
        execConnections.filter { it.fromNodeId == nodeId && it.fromPort == port }

    /** All exec edges entering [nodeId] on any input port. */
    fun incomingExec(nodeId: NodeId): List<ExecConnection> =
        execConnections.filter { it.toNodeId == nodeId }

    /** All data edges entering [nodeId] on any input port. */
    fun incomingData(nodeId: NodeId): List<DataConnection> =
        dataConnections.filter { it.toNodeId == nodeId }

    /** All data edges entering [nodeId] on the given input port. */
    fun incomingData(nodeId: NodeId, port: PortName): List<DataConnection> =
        dataConnections.filter { it.toNodeId == nodeId && it.toPort == port }

    companion object {
        const val CURRENT_SCHEMA_VERSION = 9
    }
}
