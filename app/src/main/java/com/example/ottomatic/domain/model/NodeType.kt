package com.example.ottomatic.domain.model

import com.example.ottomatic.core.model.NodeTypeId
import com.example.ottomatic.core.model.PortName
import com.example.ottomatic.core.permissions.PermissionRequirement
import com.example.ottomatic.domain.model.schema.ItemSchema

/**
 * The high-level category of a node.
 *
 * [VALUE] nodes are the pull side of the graph: a side-effect-free reader with a
 * single DATA output port and *no* EXECUTION ports at all. They are never pulsed;
 * they are read on demand, immediately before whichever node consumes them (see
 * [com.example.ottomatic.engine.WorkflowExecutor]). That is also what lets
 * `action.if` name one as its source without drawing an edge — see
 * [com.example.ottomatic.domain.model.ValueSource].
 *
 * There is deliberately no CONDITION kind. A condition is not a node family but a
 * *comparison over a value*: the device properties are declared once as [VALUE]
 * nodes, and the single comparison is the `action.if` [ACTION].
 */
enum class NodeKind {
    TRIGGER,
    ACTION,
    VALUE,
}

/** A user-facing group for a [NodeTypeDefinition] in the node palette. */
enum class NodeCategory(
    val kind: NodeKind,
    val displayName: String,
) {
    MANUAL(NodeKind.TRIGGER, "Manual"),
    TIME_SCHEDULE(NodeKind.TRIGGER, "Time & Schedule"),
    MESSAGING(NodeKind.TRIGGER, "Messaging"),
    POWER_BATTERY(NodeKind.TRIGGER, "Power & Battery"),
    LOCATION(NodeKind.TRIGGER, "Location"),
    AUTOMATION(NodeKind.TRIGGER, "Automation"),
    VARIABLES(NodeKind.TRIGGER, "Variables"),
    CONNECTIVITY(NodeKind.TRIGGER, "Connectivity"),
    PHONE_MEDIA(NodeKind.TRIGGER, "Phone & Media"),
    DEVICE_STATE(NodeKind.TRIGGER, "Device State"),
    FLOW_CONTROL(NodeKind.ACTION, "Flow Control"),
    NETWORK(NodeKind.ACTION, "Network"),
    NOTIFICATIONS(NodeKind.ACTION, "Notifications"),
    TIMING(NodeKind.ACTION, "Timing"),
    DEVICE_SETTINGS(NodeKind.ACTION, "Device Settings"),
    DATA(NodeKind.ACTION, "Data"),
    VALUE_POWER(NodeKind.VALUE, "Power & Battery"),
    VALUE_CONNECTIVITY(NodeKind.VALUE, "Connectivity"),
    VALUE_DEVICE(NodeKind.VALUE, "Device State"),
}

/** Whether a port carries control-flow pulse or a typed data value. */
enum class PortKind {
    EXECUTION,
    DATA,
}

/** Direction of a [Port] relative to the node. */
enum class Direction {
    IN,
    OUT,
}

/** How many items a data port may carry. EXECUTION ports always [ONE]. */
enum class Cardinality {
    ONE,
    MANY,
}

/**
 * A connection point on a node.
 *
 * EXECUTION ports carry no data — they are the "execute-next" pulse that
 * determines the order in which nodes run. DATA ports carry a typed
 * [Item][com.example.ottomatic.domain.model.schema.Item] whose shape is
 * described by [schema]. [schema] is null for EXECUTION ports.
 */
data class Port(
    val name: PortName,
    val kind: PortKind,
    val direction: Direction,
    val schema: ItemSchema? = null,
    val cardinality: Cardinality = Cardinality.ONE,
    val label: String = name.value,
)

/**
 * Static description of a node type: what it is called, what it does and
 * which ports (execution and data) it exposes.
 *
 * [hasDynamicPorts] marks node types whose effective port set depends on the
 * placed node's connections (e.g. `action.break` derives its field output ports
 * from the schema of whatever is connected to its `struct` input). Callers
 * that need a placed node's actual ports must use
 * [com.example.ottomatic.domain.registry.effectivePorts] instead of reading
 * [ports] directly.
 */
data class NodeTypeDefinition(
    val typeId: NodeTypeId,
    val displayName: String,
    val description: String,
    val kind: NodeKind,
    val category: NodeCategory,
    val ports: List<Port>,
    val icon: NodeIcon,
    val hasDynamicPorts: Boolean = false,
    val permissionRequirements: List<PermissionRequirement> = emptyList(),
) {
    /** All input ports (any kind). */
    val inputPorts: List<Port> get() = ports.filter { it.direction == Direction.IN }

    /** All output ports (any kind). */
    val outputPorts: List<Port> get() = ports.filter { it.direction == Direction.OUT }

    /** Input ports of a specific [kind]. */
    fun inputs(kind: PortKind): List<Port> = ports.filter { it.direction == Direction.IN && it.kind == kind }

    /** Output ports of a specific [kind]. */
    fun outputs(kind: PortKind): List<Port> = ports.filter { it.direction == Direction.OUT && it.kind == kind }

    /** Look up a port by name. */
    fun port(name: PortName): Port? = ports.firstOrNull { it.name == name }

    /** Look up a port by name and kind, throwing if missing or the wrong kind. */
    fun requirePort(name: PortName, kind: PortKind, direction: Direction): Port =
        port(name)
            ?.takeIf { it.kind == kind && it.direction == direction }
            ?: error("Node type $typeId has no $kind $direction port named '$name'")
}
