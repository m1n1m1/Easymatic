package com.example.ottomatic.domain.model

import com.example.ottomatic.core.model.ConfigKey
import com.example.ottomatic.core.model.NodeId
import com.example.ottomatic.core.model.NodeTypeId
import com.example.ottomatic.core.model.PortName
import kotlinx.serialization.Serializable

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
 * A node carries no conditions of its own. Running only when something is true is
 * expressed by placing `action.if` upstream, where the branch is visible on the
 * canvas rather than hidden in a settings blob.
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
 *
 * [variables] are this workflow's *own* variables, as opposed to the shared global
 * ones. They live here rather than in a library of their own because everything
 * that has to resolve one already holds the workflow: `effectivePorts` takes it,
 * `GraphValidator` takes it, the snapshot `arm()` loads is one, and the editor's
 * debounced save writes it. Deleting the macro takes them with it, exactly as it
 * takes the run log.
 *
 * [icon] and [accent] are how the macro identifies itself away from the canvas —
 * on its list row, on a home-screen tile and on a pinned shortcut, where the name
 * alone is a wall of text and eight identical bolts cannot be told apart. They are
 * **added with defaults and without a [CURRENT_SCHEMA_VERSION] bump**, which is
 * load-bearing: `WorkflowRepository.load` discards anything below the current
 * version rather than migrating it, so bumping for a colour would delete every
 * workflow on the device. A missing key decodes to the default instead, and
 * `WorkflowAppearanceTest` pins that.
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
    val variables: List<VariableDeclaration> = emptyList(),
    val icon: MacroIcon = MacroIcon.BOLT,
    val accent: MacroAccent = MacroAccent.SYSTEM,
) {
    /** The local declaration [id] names, or null when it was deleted. */
    fun variable(id: String): VariableDeclaration? = variables.firstOrNull { it.id == id }

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

    /**
     * Everything about this graph that the engine actually reads when it arms and
     * runs the workflow — deliberately excluding the purely cosmetic fields
     * ([WorkflowNode.x], [WorkflowNode.y], [WorkflowNode.name],
     * [WorkflowNode.visibleDataInputs], [icon], [accent]) and the
     * editor-irrelevant [schemaVersion].
     *
     * The editor compares this against the signature it last armed to decide
     * whether a save needs to re-arm the running macro. Dragging a node or
     * renaming it therefore costs nothing, while a config edit or a new edge
     * re-arms. Re-arming is not free (it re-registers geofences and re-enqueues
     * periodic work), so the gate matters — and it is why [icon] and [accent] are
     * out: recolouring a tile must not re-register a geofence.
     *
     * [variables] is in here in full, name included, because a declaration is not
     * cosmetic: its type retypes a port, its initial value is what an unset read
     * returns, and its constant flag decides whether a write is refused. The runner
     * snapshots the declarations when it arms, so an edit that did not re-arm would
     * leave the live macro running against the old ones.
     */
    fun runtimeSignature(): RuntimeSignature = RuntimeSignature(
        nodes = nodes.map { RuntimeNode(it.id, it.typeId, it.config) },
        execConnections = execConnections,
        dataConnections = dataConnections,
        enabled = enabled,
        variables = variables,
    )

    /** The execution-relevant projection of a [WorkflowNode]. */
    data class RuntimeNode(
        val id: NodeId,
        val typeId: NodeTypeId,
        val config: Map<ConfigKey, String>,
    )

    /** Opaque value compared for equality only; see [runtimeSignature]. */
    data class RuntimeSignature(
        val nodes: List<RuntimeNode>,
        val execConnections: List<ExecConnection>,
        val dataConnections: List<DataConnection>,
        val enabled: Boolean,
        val variables: List<VariableDeclaration>,
    )

    companion object {
        const val CURRENT_SCHEMA_VERSION = 10
    }
}
