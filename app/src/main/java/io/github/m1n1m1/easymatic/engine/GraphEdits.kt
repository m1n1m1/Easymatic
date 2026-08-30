// One function per kind of edit, which is what makes each one nameable and testable on
// its own; a class wrapping them would only add a receiver every caller already has.
@file:Suppress("TooManyFunctions")

package io.github.m1n1m1.easymatic.engine

import io.github.m1n1m1.easymatic.core.model.ConfigKey
import io.github.m1n1m1.easymatic.core.model.NodeId
import io.github.m1n1m1.easymatic.core.model.NodeTypeId
import io.github.m1n1m1.easymatic.core.model.PortName
import io.github.m1n1m1.easymatic.domain.model.ApiTokens
import io.github.m1n1m1.easymatic.domain.model.DataConnection
import io.github.m1n1m1.easymatic.domain.model.Direction
import io.github.m1n1m1.easymatic.domain.model.ExecConnection
import io.github.m1n1m1.easymatic.domain.model.ExecPorts
import io.github.m1n1m1.easymatic.domain.model.NodeTypeDefinition
import io.github.m1n1m1.easymatic.domain.model.Port
import io.github.m1n1m1.easymatic.domain.model.PortKind
import io.github.m1n1m1.easymatic.domain.model.Workflow
import io.github.m1n1m1.easymatic.domain.model.WorkflowNode
import io.github.m1n1m1.easymatic.domain.model.schema.conversionTarget
import io.github.m1n1m1.easymatic.domain.registry.API_INPUTS_KEY
import io.github.m1n1m1.easymatic.domain.registry.API_TOKEN_KEY
import io.github.m1n1m1.easymatic.domain.registry.API_TRIGGER_TYPE_ID
import io.github.m1n1m1.easymatic.domain.registry.COMPARISON_TYPE_IDS
import io.github.m1n1m1.easymatic.domain.registry.CONVERT_IN
import io.github.m1n1m1.easymatic.domain.registry.CONVERT_TO_KEY
import io.github.m1n1m1.easymatic.domain.registry.CONVERT_TYPE_ID
import io.github.m1n1m1.easymatic.domain.registry.DIALOG_INPUT_TYPE_ID
import io.github.m1n1m1.easymatic.domain.registry.DIALOG_INPUT_TYPE_KEY
import io.github.m1n1m1.easymatic.domain.registry.DIALOG_TIMEOUT_KEY
import io.github.m1n1m1.easymatic.domain.registry.DIALOG_TYPE_IDS
import io.github.m1n1m1.easymatic.domain.registry.IF_SOURCE_IN
import io.github.m1n1m1.easymatic.domain.registry.IF_TYPE_CONFIG_KEY
import io.github.m1n1m1.easymatic.domain.registry.IF_VALUE_IN
import io.github.m1n1m1.easymatic.domain.registry.JSON_READ_LIST_KEY
import io.github.m1n1m1.easymatic.domain.registry.JSON_READ_TYPE_ID
import io.github.m1n1m1.easymatic.domain.registry.JSON_READ_TYPE_KEY
import io.github.m1n1m1.easymatic.domain.registry.NOTIFY_ANSWER_KEYS
import io.github.m1n1m1.easymatic.domain.registry.NOTIFY_TYPE_ID
import io.github.m1n1m1.easymatic.domain.registry.NodeTypeRegistry
import io.github.m1n1m1.easymatic.domain.registry.SCRIPT_INPUTS_KEY
import io.github.m1n1m1.easymatic.domain.registry.SCRIPT_OUTPUTS_KEY
import io.github.m1n1m1.easymatic.domain.registry.SCRIPT_TYPE_ID
import io.github.m1n1m1.easymatic.domain.registry.TRANSFORM_OUT
import io.github.m1n1m1.easymatic.domain.registry.effectiveInputPorts
import io.github.m1n1m1.easymatic.domain.registry.effectiveOutputPorts
import io.github.m1n1m1.easymatic.domain.registry.generatedTarget
import io.github.m1n1m1.easymatic.domain.registry.isDataAssignable
import io.github.m1n1m1.easymatic.domain.registry.keysScopedBy
import io.github.m1n1m1.easymatic.domain.registry.notifyIsAnswerable
import io.github.m1n1m1.easymatic.domain.registry.withJsonValue
import java.util.UUID

/**
 * One port of one placed node, as an edit names it.
 *
 * `feature/`'s `PortRef` says the same thing and cannot be used here: this file is
 * in `engine/`, which may not reach the editor. The two convert in one line at the
 * boundary, which is cheaper than moving a type the whole gesture layer is written
 * around.
 */
data class PortAddress(
    val nodeId: NodeId,
    val portName: PortName,
    val kind: PortKind,
    val isOutput: Boolean,
) {
    val direction: Direction get() = if (isOutput) Direction.OUT else Direction.IN
}

/** A freshly minted connection id. */
fun randomEdgeId(): String = UUID.randomUUID().toString()

/** A freshly minted node id. */
fun randomNodeId(): NodeId = NodeId(UUID.randomUUID().toString())

/**
 * The placed [Port] [address] names, honouring dynamic and retyped ports.
 *
 * `effectivePorts` and not [NodeTypeDefinition.port]: `action.break` sprouts a port
 * per field of whatever feeds it and `transform.convert` retypes its output from its
 * config, so the declaration is not the whole truth for either.
 */
@Suppress("ReturnCount") // A node that is gone, a type this build lacks, then the port itself.
fun Workflow.portAt(address: PortAddress): Port? {
    val node = node(address.nodeId) ?: return null
    val definition = NodeTypeRegistry.byId(node.typeId) ?: return null
    val ports = if (address.isOutput) {
        effectiveOutputPorts(definition, this, node)
    } else {
        effectiveInputPorts(definition, this, node)
    }
    return ports.firstOrNull { it.name == address.portName && it.kind == address.kind }
}

/** Appends the exec or data edge [output] to [input] to this workflow. */
fun Workflow.withConnection(
    output: PortAddress,
    input: PortAddress,
    newEdgeId: () -> String = ::randomEdgeId,
): Workflow = when (output.kind) {
    PortKind.EXECUTION -> copy(
        execConnections = execConnections + ExecConnection(
            id = newEdgeId(),
            fromNodeId = output.nodeId,
            fromPort = output.portName,
            toNodeId = input.nodeId,
            toPort = input.portName,
        ),
    )
    PortKind.DATA -> copy(
        dataConnections = dataConnections + DataConnection(
            id = newEdgeId(),
            fromNodeId = output.nodeId,
            fromPort = output.portName,
            toNodeId = input.nodeId,
            toPort = input.portName,
        ),
    )
}

/** Whether this exact edge is already drawn. */
fun Workflow.connectionExists(output: PortAddress, input: PortAddress): Boolean =
    when (output.kind) {
        PortKind.EXECUTION -> execConnections.any {
            it.fromNodeId == output.nodeId && it.fromPort == output.portName &&
                it.toNodeId == input.nodeId && it.toPort == input.portName
        }
        PortKind.DATA -> dataConnections.any {
            it.fromNodeId == output.nodeId && it.fromPort == output.portName &&
                it.toNodeId == input.nodeId && it.toPort == input.portName
        }
    }

/**
 * Schema subtyping check for a candidate DATA edge from [output] to [input],
 * mirroring `GraphValidator` so an incompatible edge is refused where it is made
 * rather than reported afterwards. Wildcard ports — `action.break`'s struct input —
 * accept anything.
 */
fun Workflow.isTypeCompatible(output: PortAddress, input: PortAddress): Boolean =
    isDataAssignable(portAt(output), portAt(input))

/** A `transform.convert` placed into a wire, and the node it added. */
data class Autocast(val workflow: Workflow, val convertId: NodeId)

/**
 * Bridges a DATA join the type system refused, by placing a `transform.convert` node
 * into the wire pre-set to the conversion that fits — Unreal Blueprints' autocast,
 * and the reason a mismatched drop is not simply thrown away.
 *
 * The conversion is a real node rather than a coercion on the edge, so it is visible,
 * deletable, and carries its own "If it fails" setting. A join with no conversion at
 * all (text into a struct) answers null and is still refused.
 *
 * [place] is handed the Convert definition and answers its **top-left** in graph
 * units. It is a parameter because where an inserted node belongs is a fact about the
 * surface that asked for it: halfway along the wire a finger drew, or wherever the
 * layout puts it for the assistant.
 */
@Suppress(
    // Two endpoints and three injected collaborators; a holder would only rename them.
    "LongParameterList",
    // Two ways there is no conversion to make, then the one there is.
    "ReturnCount",
)
fun Workflow.withAutocast(
    output: PortAddress,
    input: PortAddress,
    place: (NodeTypeDefinition) -> Pair<Float, Float>,
    nameOf: (NodeTypeDefinition) -> String,
    newNodeId: () -> NodeId = ::randomNodeId,
    newEdgeId: () -> String = ::randomEdgeId,
): Autocast? {
    val to = conversionTarget(portAt(output)?.schema, portAt(input)?.schema) ?: return null
    val definition = NodeTypeRegistry.byId(CONVERT_TYPE_ID) ?: return null
    val (x, y) = place(definition)
    val convert = WorkflowNode(
        id = newNodeId(),
        typeId = CONVERT_TYPE_ID,
        name = nameOf(definition),
        x = x,
        y = y,
        config = mapOf(CONVERT_TO_KEY to to.name),
        // The value input is a wildcard rather than a `@Wired` property, but every
        // DATA input starts hidden — reveal it or the edge lands nowhere.
        visibleDataInputs = setOf(CONVERT_IN),
    )
    val intoConvert = PortAddress(convert.id, CONVERT_IN, PortKind.DATA, isOutput = false)
    val outOfConvert = PortAddress(convert.id, TRANSFORM_OUT, PortKind.DATA, isOutput = true)
    return Autocast(
        workflow = copy(nodes = nodes + convert)
            .withConnection(output, intoConvert, newEdgeId)
            .withConnection(outOfConvert, input, newEdgeId),
        convertId = convert.id,
    )
}

/**
 * This workflow with [input]'s handle drawn, when it is a DATA input that starts
 * hidden.
 *
 * A `@Wired` config property is a DATA input port as well, and those stay hidden
 * until the socket toggle beside the form row opts in. An edge landing on one that is
 * still hidden saves perfectly and draws nowhere, which is the failure
 * `addNodeConnectedTo` already guards against for the drag-to-create path.
 */
fun Workflow.withRevealedInput(input: PortAddress): Workflow {
    if (input.kind != PortKind.DATA || input.isOutput) return this
    return copy(
        nodes = nodes.map { node ->
            if (node.id != input.nodeId) {
                node
            } else {
                node.copy(visibleDataInputs = node.visibleDataInputs + input.portName)
            }
        },
    )
}

/**
 * The config a freshly placed node of [typeId] starts with, where "empty" is the
 * wrong answer.
 *
 * There is one case, and it is the only kind there can be: a value that must be
 * *generated* rather than chosen or typed. `trigger.api`'s key is minted here so the
 * node is callable the moment it is placed — an empty field would make the commonest
 * setup a two-step one, and the step nobody would guess at.
 *
 * Shared by every placement path deliberately. The palette, the drag-to-create flow
 * and the assistant each build a [WorkflowNode], and a node created one way is no less
 * real than one created another; a key on some of them would be a node that works or
 * does not depending on how it was made.
 */
fun initialConfigFor(typeId: NodeTypeId): Map<ConfigKey, String> = when (typeId) {
    API_TRIGGER_TYPE_ID -> mapOf(API_TOKEN_KEY to ApiTokens.generate())
    else -> emptyMap()
}

/**
 * This workflow with [key] set on [nodeId], and everything that edit invalidated
 * cleared out.
 *
 * Setting a config value is not the one-line write it looks like. Three things ride
 * on it and all three used to live in the editor, where a finger was the only thing
 * that could ever cause one:
 *
 *  - a **generated** field has no config key of its own and belongs inside another
 *    property's JSON;
 *  - a value that **scopes** another falsifies it — a service its entity can no longer
 *    accept still renders perfectly, because a scoped picker draws the name cached
 *    inside its reference;
 *  - a handful of keys **retype the node's ports**, and an edge left on a port that no
 *    longer exists is worse than no edge: it draws, it saves, and it silently carries
 *    nothing.
 *
 * The assistant sets config values too, so all three are facts about the graph rather
 * than about the form.
 */
fun Workflow.withConfig(nodeId: NodeId, key: ConfigKey, value: String): Workflow {
    val typeId = node(nodeId)?.typeId ?: return this
    val updated = copy(
        nodes = nodes.map { node ->
            if (node.id != nodeId) node else node.copy(config = node.config.after(key, value, typeId))
        },
    )
    return updated.prunedAfterConfigEdit(nodeId, key)
}

/**
 * This config with [key] set, and anything that scoped by it cleared.
 *
 * The sibling of [prunedAfterConfigEdit] — that one drops edges the edit invalidated,
 * this one drops *values* it invalidated. Only when the new value is **non-blank and
 * different**: clearing a hub back to blank means "any hub", under which the entity
 * beside it is still entirely coherent, and blanking it there would be gratuitous.
 */
@Suppress("ReturnCount") // A generated write, then an edit that clears nothing, then one that does.
private fun Map<ConfigKey, String>.after(key: ConfigKey, value: String, typeId: NodeTypeId): Map<ConfigKey, String> {
    // A generated field has no config key of its own: its value belongs inside another
    // property's JSON. Routing it here rather than in the widget is what keeps
    // `WorkflowNode.config` a flat map that nothing else has to learn about.
    generatedTarget(key)?.let { (backing, name) ->
        return this + (backing to withJsonValue(this[backing].orEmpty(), name, value))
    }
    val updated = this + (key to value)
    if (value.isBlank() || this[key] == value) return updated
    return updated - keysScopedBy(typeId, key)
}

/**
 * Drops the edges a config change has just invalidated.
 *
 * Every case is gated on the node's own typeId as well as the key, because none of
 * "type", "outputs" or "timeoutSeconds" is a reserved config name.
 */
private fun Workflow.prunedAfterConfigEdit(nodeId: NodeId, key: ConfigKey): Workflow {
    val typeId = node(nodeId)?.typeId
    return when {
        // A comparison's type chooser (`action.if`, `action.while`): the source/value
        // schemas are about to change and the old connections would likely fail the
        // new check.
        key == IF_TYPE_CONFIG_KEY && typeId in COMPARISON_TYPE_IDS -> copy(
            dataConnections = dataConnections.filterNot {
                it.toNodeId == nodeId && (it.toPort == IF_SOURCE_IN || it.toPort == IF_VALUE_IN)
            },
        )
        retypesDataPorts(key, typeId) ->
            copy(dataConnections = dataConnections.filter { it.stillValid(this, nodeId) })
        key == DIALOG_TIMEOUT_KEY && typeId in DIALOG_TYPE_IDS -> withoutStrandedTimeoutBranch(nodeId)
        // `action.notify` loses a whole branch rather than one route when the last
        // thing that could be reacted to is switched off, so both halves have to go:
        // the exec wire here, and the three data wires that ride on it below.
        key in NOTIFY_ANSWER_KEYS && typeId == NOTIFY_TYPE_ID ->
            withoutStrandedAnswerBranch(nodeId)
                .let { it.copy(dataConnections = it.dataConnections.filter { edge -> edge.stillValid(it, nodeId) }) }
        else -> this
    }
}

/** The two `@Ports` config keys on `action.script`, both of which retype its ports. */
private val SCRIPT_PORT_KEYS = setOf(SCRIPT_INPUTS_KEY, SCRIPT_OUTPUTS_KEY)

private val JSON_READ_PORT_KEYS = setOf(JSON_READ_TYPE_KEY, JSON_READ_LIST_KEY)

/**
 * Whether [key] rewrites the DATA ports of a node of type [typeId], so that every edge
 * touching it has to be re-checked.
 *
 * Re-checking beats dropping every edge the way `action.if`'s type chooser does: a port
 * list is edited one character at a time, so clearing the lot on each keystroke would
 * delete work the user can see is still correct.
 */
private fun retypesDataPorts(key: ConfigKey, typeId: NodeTypeId?): Boolean = when (typeId) {
    // An edited row can rename a port, delete it or retype it.
    SCRIPT_TYPE_ID -> key in SCRIPT_PORT_KEYS
    // The same editor on the same config key, one direction instead of two.
    API_TRIGGER_TYPE_ID -> key == API_INPUTS_KEY
    // The result type and the list switch both retype the one output port, so an edge
    // that fitted a Text no longer fits a list of them.
    JSON_READ_TYPE_ID -> key in JSON_READ_PORT_KEYS
    // A dialog's answer type retypes its one output port, exactly as a JSON read's does.
    DIALOG_INPUT_TYPE_ID -> key == DIALOG_INPUT_TYPE_KEY
    else -> false
}

/**
 * Drops the wire leaving [nodeId]'s `timed_out` port once that port has stopped being
 * drawn — i.e. once the dialog waits forever again.
 *
 * The only *exec* edge any of this prunes, and it has to happen here: `GraphValidator`
 * resolves exec edges against the static declaration, where the port still exists, so
 * nothing downstream would ever report the wire. It would simply stop being drawn and
 * never fire again.
 */
private fun Workflow.withoutStrandedTimeoutBranch(nodeId: NodeId): Workflow {
    val waits = (node(nodeId)?.config?.get(DIALOG_TIMEOUT_KEY)?.toIntOrNull() ?: 0) > 0
    if (waits) return this
    return copy(
        execConnections = execConnections.filterNot {
            it.fromNodeId == nodeId && it.fromPort == ExecPorts.TIMED_OUT
        },
    )
}

/**
 * Drops the wire leaving [nodeId]'s `resumed` port once `action.notify` has stopped
 * offering anything to react to.
 *
 * [withoutStrandedTimeoutBranch]'s job for [withoutStrandedTimeoutBranch]'s reason, and
 * the second exec edge any of this prunes. The difference is only in what makes the
 * port go away: there it is one number, here it is three fields between them saying the
 * notification can be answered at all.
 */
private fun Workflow.withoutStrandedAnswerBranch(nodeId: NodeId): Workflow {
    if (notifyIsAnswerable(node(nodeId)?.config.orEmpty())) return this
    return copy(
        execConnections = execConnections.filterNot {
            it.fromNodeId == nodeId && it.fromPort == ExecPorts.RESUMED
        },
    )
}

/** True when this edge still connects two ports that exist and type-check. */
private fun DataConnection.stillValid(workflow: Workflow, nodeId: NodeId): Boolean {
    if (toNodeId != nodeId && fromNodeId != nodeId) return true
    val from = workflow.portAt(PortAddress(fromNodeId, fromPort, PortKind.DATA, isOutput = true))
    val to = workflow.portAt(PortAddress(toNodeId, toPort, PortKind.DATA, isOutput = false))
    return from != null && to != null && isDataAssignable(from, to)
}

/**
 * This workflow with [nodeId] moved to [x], [y] in graph units.
 *
 * Positions are the one part of a node that nothing else reads: `runtimeSignature` omits
 * them, which is why dragging a card never re-arms a macro. Moving one is therefore as
 * cheap an edit as there is, and the only reason to be careful about it is that the
 * canvas is the user's own arrangement.
 */
fun Workflow.withNodeAt(nodeId: NodeId, x: Float, y: Float): Workflow = copy(
    nodes = nodes.map { node -> if (node.id == nodeId) node.copy(x = x, y = y) else node },
)

/** This workflow without [nodeId] and every edge that touched it. */
fun Workflow.withoutNode(nodeId: NodeId): Workflow = copy(
    nodes = nodes.filterNot { it.id == nodeId },
    execConnections = execConnections.filterNot { it.fromNodeId == nodeId || it.toNodeId == nodeId },
    dataConnections = dataConnections.filterNot { it.fromNodeId == nodeId || it.toNodeId == nodeId },
)

/** This workflow without the edge [connectionId], whichever channel it is on. */
fun Workflow.withoutConnection(connectionId: String): Workflow = copy(
    execConnections = execConnections.filterNot { it.id == connectionId },
    dataConnections = dataConnections.filterNot { it.id == connectionId },
)
