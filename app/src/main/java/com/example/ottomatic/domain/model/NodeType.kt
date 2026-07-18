package com.example.ottomatic.domain.model

import com.example.ottomatic.domain.model.schema.ItemSchema

/**
 * The high-level category of a node.
 */
enum class NodeKind {
    TRIGGER,
    ACTION,
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
    val name: String,
    val kind: PortKind,
    val direction: Direction,
    val schema: ItemSchema? = null,
    val cardinality: Cardinality = Cardinality.ONE,
    val label: String = name,
)

/**
 * Static description of a node type: what it is called, what it does and
 * which ports (execution and data) it exposes.
 *
 * [iconKey] is a platform-agnostic identifier that the UI layer maps to an icon.
 */
data class NodeTypeDefinition(
    val typeId: String,
    val displayName: String,
    val description: String,
    val kind: NodeKind,
    val ports: List<Port>,
    val iconKey: String,
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
    fun port(name: String): Port? = ports.firstOrNull { it.name == name }

    /** Look up a port by name and kind, throwing if missing or the wrong kind. */
    fun requirePort(name: String, kind: PortKind, direction: Direction): Port =
        port(name)
            ?.takeIf { it.kind == kind && it.direction == direction }
            ?: error("Node type $typeId has no $kind $direction port named '$name'")
}
