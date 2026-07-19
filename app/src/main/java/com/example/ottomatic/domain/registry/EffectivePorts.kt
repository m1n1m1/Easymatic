package com.example.ottomatic.domain.registry

import com.example.ottomatic.domain.model.Direction
import com.example.ottomatic.domain.model.NodeKind
import com.example.ottomatic.domain.model.NodeTypeDefinition
import com.example.ottomatic.domain.model.Port
import com.example.ottomatic.domain.model.PortKind
import com.example.ottomatic.domain.model.Workflow
import com.example.ottomatic.domain.model.WorkflowNode
import com.example.ottomatic.domain.model.schema.ItemSchema

/**
 * Resolves the *effective* port set of a placed [WorkflowNode].
 *
 * Most node types have a static port list ([NodeTypeDefinition.ports]).
 * Two independent mechanisms extend that static set on a *placed* node:
 *
 *  1. **Exposed config inputs** — any ACTION node whose
 *     [WorkflowNode.exposedInputs] is non-empty gains one typed DATA input
 *     port per exposed config field (see [ConfigSchemaRegistry]). This is the
 *     primary way upstream data feeds into a node's configurable fields: the
 *     user toggles "Expose as data input" on a field in the configure sheet,
 *     wires an edge into the new port, and the incoming item overrides the
 *     static config value for that field at runtime. Replaces the former
 *     batch `config` map DATA input port and the `action.make` struct node.
 *
 *  2. **Dynamic struct ports** — `action.break` ([hasDynamicPorts] = true)
 *     derives one DATA output port per field of the struct connected to its
 *     `struct` input, by following the incoming edge back to its source port
 *     and reading that source's [ItemSchema]. Recursion through other dynamic
 *     nodes is safe because the graph validator guarantees data-edge
 *     acyclicity.
 */

/** EXECUTION input port. */
private fun execIn(name: String = "in"): Port =
    Port(name = name, kind = PortKind.EXECUTION, direction = Direction.IN)

/** EXECUTION output port. */
private fun execOut(name: String = "out"): Port =
    Port(name = name, kind = PortKind.EXECUTION, direction = Direction.OUT)

/** A DATA port with an explicit [ItemSchema]. */
private fun dataPort(name: String, direction: Direction, schema: ItemSchema): Port =
    Port(name = name, kind = PortKind.DATA, direction = direction, schema = schema)

/** The struct input port name on `action.break`. */
const val BREAK_STRUCT_IN = "struct"

/** typeId of the adaptive break-struct node. */
const val BREAK_TYPE_ID = "action.break"

/**
 * The effective ports for the placed [node] in [workflow]: the node type's
 * static [ports] (or the dynamic struct-derived ports for `action.break`),
 * plus one DATA input port per exposed config field on [node].
 */
fun effectivePorts(
    definition: NodeTypeDefinition,
    workflow: Workflow,
    node: WorkflowNode,
): List<Port> {
    val base = if (definition.hasDynamicPorts && definition.typeId == BREAK_TYPE_ID) {
        breakEffectivePorts(workflow, node)
    } else {
        definition.ports
    }
    return base + exposedFieldInputPorts(definition, node)
}

/** Effective input ports (convenience filter over [effectivePorts]). */
fun effectiveInputPorts(
    definition: NodeTypeDefinition,
    workflow: Workflow,
    node: WorkflowNode,
): List<Port> = effectivePorts(definition, workflow, node).filter { it.direction == Direction.IN }

/** Effective output ports (convenience filter over [effectivePorts]). */
fun effectiveOutputPorts(
    definition: NodeTypeDefinition,
    workflow: Workflow,
    node: WorkflowNode,
): List<Port> = effectivePorts(definition, workflow, node).filter { it.direction == Direction.OUT }

/**
 * Looks up a port by [name] on the placed [node], honouring dynamic and exposed
 * ports. Use this instead of [NodeTypeDefinition.port] for any placed node.
 *
 * [direction] is an optional filter. Supply it when the caller knows which
 * side of the node the port lives on: a node may expose a config field whose
 * key collides with a static output port name (e.g. `action.wifi` has a
 * `state` DATA output and a `state` config field that can be exposed as a
 * DATA input), so a name-only lookup is ambiguous on such nodes.
 */
fun effectivePort(
    definition: NodeTypeDefinition,
    workflow: Workflow,
    node: WorkflowNode,
    name: String,
    direction: Direction? = null,
): Port? = effectivePorts(definition, workflow, node).firstOrNull {
    it.name == name && (direction == null || it.direction == direction)
}

/**
 * Ports for an `action.break` node: base (exec in/out + struct IN wildcard)
 * plus one DATA OUT per field of the struct connected to [BREAK_STRUCT_IN].
 */
private fun breakEffectivePorts(workflow: Workflow, node: WorkflowNode): List<Port> {
    val base = listOf(
        execIn(),
        execOut(),
        dataPort(BREAK_STRUCT_IN, Direction.IN, ItemSchema.Wildcard),
    )
    val schema = resolveStructSchema(workflow, node, BREAK_STRUCT_IN) as? ItemSchema.Object
        ?: return base
    return base + schema.fields.map { (name, fieldSchema) ->
        dataPort(name, Direction.OUT, fieldSchema)
    }
}

/**
 * The typed DATA input ports added to [node] for each config field the user
 * has toggled as exposed ([WorkflowNode.exposedInputs]). Each port is named
 * by the field key and typed by [ConfigField.portSchema]. Returns empty for
 * non-ACTION nodes (triggers are sources, not consumers) or nodes with no
 * config schema / no exposed fields.
 */
private fun exposedFieldInputPorts(definition: NodeTypeDefinition, node: WorkflowNode): List<Port> {
    val schema = if (definition.kind == NodeKind.ACTION && node.exposedInputs.isNotEmpty()) {
        ConfigSchemaRegistry.byId(definition.typeId)
    } else {
        null
    } ?: return emptyList()
    return schema.fields
        .filter { it.key in node.exposedInputs }
        .map { field -> dataPort(field.key, Direction.IN, field.portSchema()) }
}

/**
 * Resolves the [ItemSchema] of the item arriving on the struct input port
 * named [inputPortName] of [node], by following the incoming DATA edge back
 * to its source port. Returns null when no edge is wired or the source has no
 * (or non-object for the object-typed callers) schema.
 */
@Suppress("ReturnCount") // Null-guards on the optional edge/node/def path are idiomatic here.
private fun resolveStructSchema(
    workflow: Workflow,
    node: WorkflowNode,
    inputPortName: String,
): ItemSchema? {
    val edge = workflow.incomingData(node.id, inputPortName).firstOrNull() ?: return null
    val sourceNode = workflow.node(edge.fromNodeId) ?: return null
    val sourceDef = NodeTypeRegistry.byId(sourceNode.typeId) ?: return null
    val sourcePorts = if (sourceDef.hasDynamicPorts) {
        effectivePorts(sourceDef, workflow, sourceNode)
    } else {
        sourceDef.ports
    }
    return sourcePorts.firstOrNull {
        it.name == edge.fromPort && it.kind == PortKind.DATA && it.direction == Direction.OUT
    }?.schema
}
