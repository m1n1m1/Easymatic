package com.example.ottomatic.engine.ai

import com.example.ottomatic.core.model.ConfigKey
import com.example.ottomatic.core.model.NodeId
import com.example.ottomatic.core.model.NodeTypeId
import com.example.ottomatic.core.model.PortName
import com.example.ottomatic.core.service.AiParam
import com.example.ottomatic.core.service.AiParamSchema
import com.example.ottomatic.core.service.AiTool
import com.example.ottomatic.core.service.AiToolCall
import com.example.ottomatic.core.service.AiToolResult
import com.example.ottomatic.domain.model.Direction
import com.example.ottomatic.domain.model.MacroAccent
import com.example.ottomatic.domain.model.MacroIcon
import com.example.ottomatic.domain.model.NodeKind
import com.example.ottomatic.domain.model.NodeTypeDefinition
import com.example.ottomatic.domain.model.Port
import com.example.ottomatic.domain.model.PortKind
import com.example.ottomatic.domain.model.VariableDeclaration
import com.example.ottomatic.domain.model.Workflow
import com.example.ottomatic.domain.model.WorkflowNode
import com.example.ottomatic.domain.model.config.ValueType
import com.example.ottomatic.domain.registry.NodeTypeRegistry
import com.example.ottomatic.domain.registry.effectiveInputPorts
import com.example.ottomatic.domain.registry.effectiveOutputPorts
import com.example.ottomatic.engine.GraphLayout
import com.example.ottomatic.engine.PortAddress
import com.example.ottomatic.engine.connectionExists
import com.example.ottomatic.engine.initialConfigFor
import com.example.ottomatic.engine.isTypeCompatible
import com.example.ottomatic.engine.randomNodeId
import com.example.ottomatic.engine.validation.GraphValidator
import com.example.ottomatic.engine.validation.Severity
import com.example.ottomatic.engine.withAutocast
import com.example.ottomatic.engine.withConfig
import com.example.ottomatic.engine.withConnection
import com.example.ottomatic.engine.withNodeAt
import com.example.ottomatic.engine.withRevealedInput
import com.example.ottomatic.engine.withoutConnection
import com.example.ottomatic.engine.withoutNode
import java.util.UUID
import kotlin.math.abs
import kotlin.math.roundToInt

/** One tool call applied: the graph it produced, and what the model is told. */
data class GraphEdit(
    val workflow: Workflow,
    val result: AiToolResult,
    /** Nodes this call created, for the end-of-turn layout. */
    val addedNodes: Set<NodeId> = emptySet(),
    /**
     * Nodes this call *placed*, which the end-of-turn layout must then leave alone.
     *
     * Arranging them anyway would make `move_node` a tool whose effect is undone a moment
     * later — the automatic layout is a default, and a model that positioned something has
     * said it does not want the default there.
     */
    val movedNodes: Set<NodeId> = emptySet(),
    /** Nodes this call touched, for the canvas to follow. */
    val touchedNodes: Set<NodeId> = emptySet(),
)

/**
 * What the graph assistant may do, and what doing it means.
 *
 * **A generic tool surface rather than one tool per node type.** Handing a model a
 * hundred and seventy-five tools would spend more context on the palette than on the
 * question, and none of those tools could draw a wire. So the tools are about the
 * *graph* — add, connect, configure — and the palette arrives as data through
 * [NodeCatalog], which derives it from the same registries the Ask AI node's tool
 * schemas come from.
 *
 * The one thing that surface loses is per-node argument typing: `set_config` cannot
 * carry a particular node's enum in its own schema. `describe_node_type` closes that
 * gap by naming the exact allowed values, and a rejected value comes back naming them
 * again — so the model corrects itself in one turn rather than writing something that
 * silently means nothing.
 *
 * **Everything here is a pure function of a [Workflow].** The interesting behaviour is
 * *sequence* — a node added, wired wrong, reported, fixed — which no live model can be
 * made to produce on demand; that is `runToolExchange`'s own argument for taking its
 * transport as a parameter, and it applies with more force to the thing being driven.
 */
@Suppress(
    // One per tool, plus their shared rendering; folding them hides the surface.
    "TooManyFunctions",
    // Every early return here is a *different sentence* the model reads and acts on —
    // which node is missing, which port does not exist, which values are allowed. Folding
    // them into one exit would mean one generic failure, and a generic failure is a turn
    // spent guessing out of a capped budget. This is the file where return count is the
    // feature.
    "ReturnCount",
)
object GraphEditTools {

    const val READ_GRAPH = "read_graph"
    const val LIST_NODE_TYPES = "list_node_types"
    const val DESCRIBE_NODE_TYPE = "describe_node_type"
    const val VALIDATE = "validate"
    const val ADD_NODE = "add_node"
    const val DELETE_NODE = "delete_node"
    const val MOVE_NODE = "move_node"
    const val SET_CONFIG = "set_config"
    const val CONNECT = "connect"
    const val DISCONNECT = "disconnect"
    const val DECLARE_VARIABLE = "declare_variable"
    const val DELETE_VARIABLE = "delete_variable"
    const val SET_MACRO = "set_macro"

    private const val TYPE_ID = "type_id"
    private const val NODE_ID = "node_id"
    private const val X = "x"
    private const val Y = "y"
    private const val KEY = "key"
    private const val VALUE = "value"
    private const val NAME = "name"
    private const val KIND = "kind"
    private const val CATEGORY = "category"
    private const val SEARCH = "search"
    private const val FROM_NODE = "from_node"
    private const val FROM_PORT = "from_port"
    private const val TO_NODE = "to_node"
    private const val TO_PORT = "to_port"
    private const val CONNECTION_ID = "connection_id"
    private const val VARIABLE_ID = "variable_id"
    private const val TYPE = "type"
    private const val INITIAL_VALUE = "initial_value"
    private const val ICON = "icon"
    private const val ACCENT = "accent"

    /**
     * The tools, in the order a turn tends to use them: look, then change.
     *
     * Twelve, which is comfortably inside every provider's limit and small enough that
     * the model holds all of them in view — the thing `ToolSpec.MAX_TOOLS` warns about
     * for the Ask AI node applies here too, and here the list is fixed rather than
     * chosen, so it can simply be kept short.
     */
    val tools: List<AiTool> = listOf(
        AiTool(
            name = READ_GRAPH,
            description = "Read the workflow being edited: its nodes, their config, and the wires between them. " +
                "Call this first when you need to change something that already exists.",
        ),
        AiTool(
            name = LIST_NODE_TYPES,
            description = "List the node types available, optionally filtered. " +
                "The full list is already in your instructions; use this to narrow it.",
            parameters = listOf(
                AiParam(KIND, AiParamSchema.Text(NodeKind.entries.map { it.name }), "Only nodes of this kind"),
                AiParam(CATEGORY, AiParamSchema.Text(), "Only nodes in this palette category"),
                AiParam(SEARCH, AiParamSchema.Text(), "Only nodes whose id, name or description contains this"),
            ),
        ),
        AiTool(
            name = DESCRIBE_NODE_TYPE,
            description = "Everything about one node type: its ports, their types, and every config field " +
                "with the values each accepts. Call this before adding or configuring a node you have not used yet.",
            parameters = listOf(AiParam(TYPE_ID, AiParamSchema.Text(), "For example action.notify", required = true)),
        ),
        AiTool(
            name = VALIDATE,
            description = "Check the workflow for problems and list them. " +
                "Call this when you think you are finished, and fix whatever it reports.",
        ),
        AiTool(
            name = ADD_NODE,
            description = "Place a new node. Returns its id, which you need for connecting and configuring it. " +
                "You do not choose where it goes on the canvas.",
            parameters = listOf(
                AiParam(TYPE_ID, AiParamSchema.Text(), "For example trigger.geofence", required = true),
                AiParam(NAME, AiParamSchema.Text(), "A short label for this card. Defaults to the node's own name"),
            ),
        ),
        AiTool(
            name = DELETE_NODE,
            description = "Remove a node and every wire that touched it.",
            parameters = listOf(AiParam(NODE_ID, AiParamSchema.Text(), required = true)),
        ),
        AiTool(
            name = MOVE_NODE,
            description = "Put a node at a place on the canvas. New nodes are arranged for you, " +
                "so use this to tidy: to set which branch of an If goes on which side, or to lay out " +
                "a workflow you were asked to clean up. Graph flows downwards — y grows down — and one " +
                "step is about ${GraphLayout.COLUMN_WIDTH.toInt()} across or " +
                "${GraphLayout.ROW_HEIGHT.toInt()} down. read_graph tells you where everything is now.",
            parameters = listOf(
                AiParam(NODE_ID, AiParamSchema.Text(), required = true),
                AiParam(X, AiParamSchema.Decimal, "Left edge of the card", required = true),
                AiParam(Y, AiParamSchema.Decimal, "Top edge of the card", required = true),
            ),
        ),
        AiTool(
            name = SET_CONFIG,
            description = "Set one config field on one node. " +
                "Use describe_node_type first to learn the field names and what they accept.",
            parameters = listOf(
                AiParam(NODE_ID, AiParamSchema.Text(), required = true),
                AiParam(KEY, AiParamSchema.Text(), "The config field name", required = true),
                AiParam(VALUE, AiParamSchema.Text(), "The value, as text. Blank clears the field", required = true),
            ),
        ),
        AiTool(
            name = CONNECT,
            description = "Wire one port to another. Execution ports carry the run order; data ports carry values. " +
                "Both ends must be the same kind. A convertible type mismatch inserts a Convert node for you.",
            parameters = listOf(
                AiParam(FROM_NODE, AiParamSchema.Text(), "The node the wire leaves", required = true),
                AiParam(FROM_PORT, AiParamSchema.Text(), "An output port on that node", required = true),
                AiParam(TO_NODE, AiParamSchema.Text(), "The node the wire enters", required = true),
                AiParam(TO_PORT, AiParamSchema.Text(), "An input port on that node", required = true),
            ),
        ),
        AiTool(
            name = DISCONNECT,
            description = "Remove one wire, by the id read_graph gave it.",
            parameters = listOf(AiParam(CONNECTION_ID, AiParamSchema.Text(), required = true)),
        ),
        AiTool(
            name = DECLARE_VARIABLE,
            description = "Declare a variable belonging to this workflow, so nodes can store and read it. " +
                "Returns its id, which is what Set Variable and Variable Value point at.",
            parameters = listOf(
                AiParam(NAME, AiParamSchema.Text(), "What the user will call it", required = true),
                AiParam(TYPE, AiParamSchema.Text(ValueType.entries.map { it.name }), "Defaults to TEXT"),
                AiParam(INITIAL_VALUE, AiParamSchema.Text(), "What it reads as before anything writes to it"),
            ),
        ),
        AiTool(
            name = DELETE_VARIABLE,
            description = "Remove one of this workflow's variables.",
            parameters = listOf(AiParam(VARIABLE_ID, AiParamSchema.Text(), required = true)),
        ),
        AiTool(
            name = SET_MACRO,
            description = "Rename this workflow and pick the icon and colour it shows on the macro list, " +
                "its home-screen tile and its shortcut. Set only what you want to change.",
            parameters = listOf(
                AiParam(NAME, AiParamSchema.Text()),
                AiParam(ICON, AiParamSchema.Text(MacroIcon.entries.map { it.name })),
                AiParam(ACCENT, AiParamSchema.Text(MacroAccent.entries.map { it.name })),
            ),
        ),
    )

    /**
     * [call] applied to [workflow].
     *
     * [nameOf] names a node as it is placed. It is a parameter because a placed node's
     * name is *persisted* and is what the card draws, so it has to be the translated
     * one — and `NodeText` lives in `feature/`, which this may not reach.
     */
    // A dispatch table, not logic: one branch per tool, each delegating immediately. The
    // complexity metric counts the branches, and collapsing them into a map of lambdas
    // would hide the one thing this function is for — the list of what can be asked.
    @Suppress("CyclomaticComplexMethod")
    fun apply(
        workflow: Workflow,
        call: AiToolCall,
        nameOf: (NodeTypeDefinition) -> String = { it.displayName },
    ): GraphEdit = when (call.name) {
        READ_GRAPH -> workflow.answers(describeGraph(workflow))
        LIST_NODE_TYPES -> workflow.answers(listNodeTypes(call))
        DESCRIBE_NODE_TYPE -> workflow.answers(
            call.required(TYPE_ID)?.let { NodeCatalog.describe(NodeTypeId(it)) }
                ?: missing(TYPE_ID),
        )
        VALIDATE -> workflow.answers(describeProblems(workflow))
        ADD_NODE -> addNode(workflow, call, nameOf)
        DELETE_NODE -> deleteNode(workflow, call)
        MOVE_NODE -> moveNode(workflow, call)
        SET_CONFIG -> setConfig(workflow, call)
        CONNECT -> connect(workflow, call, nameOf)
        DISCONNECT -> disconnect(workflow, call)
        DECLARE_VARIABLE -> declareVariable(workflow, call)
        DELETE_VARIABLE -> deleteVariable(workflow, call)
        SET_MACRO -> setMacro(workflow, call)
        else -> workflow.fails("There is no tool called ${call.name}.")
    }

    // region Reading

    /**
     * The graph as text.
     *
     * Config values are included because the assistant is frequently *editing* rather
     * than building, and "what is this node set to now" is the question it has to answer
     * before it changes anything. Wire ids are included because [DISCONNECT] needs one.
     *
     * Public because the session puts it in the prompt as well as behind [READ_GRAPH]:
     * a turn that begins by knowing what is on the canvas is a turn that does not spend
     * its first call finding out.
     */
    fun describeGraph(workflow: Workflow): String = buildString {
        appendLine("Workflow \"${workflow.name}\" (icon ${workflow.icon.name}, accent ${workflow.accent.name})")
        if (workflow.variables.isEmpty()) {
            appendLine("Variables: none")
        } else {
            appendLine("Variables:")
            workflow.variables.forEach { appendLine("  ${it.id} \"${it.name}\" ${it.type.name}") }
        }
        if (workflow.nodes.isEmpty()) {
            appendLine("Nodes: none — this workflow is empty")
        } else {
            appendLine("Nodes:")
            workflow.nodes.forEach { node ->
                appendLine(
                    "  ${node.id.value} ${node.typeId.value} \"${node.name}\" " +
                        "at ${node.x.roundToInt()},${node.y.roundToInt()}",
                )
                node.config.filterValues { it.isNotBlank() }
                    .forEach { (key, value) -> appendLine("    ${key.value} = ${value.oneLine()}") }
            }
        }
        appendLine("Execution wires:")
        workflow.execConnections.forEach {
            appendLine("  ${it.id} ${describeEdge(it.fromNodeId, it.fromPort, it.toNodeId, it.toPort)}")
        }
        appendLine("Data wires:")
        workflow.dataConnections.forEach {
            appendLine("  ${it.id} ${describeEdge(it.fromNodeId, it.fromPort, it.toNodeId, it.toPort)}")
        }
    }.trimEnd()

    private fun describeEdge(from: NodeId, fromPort: PortName, to: NodeId, toPort: PortName): String =
        "${from.value}.${fromPort.value} -> ${to.value}.${toPort.value}"

    private fun listNodeTypes(call: AiToolCall): String {
        val kind = call.arg(KIND)?.let { raw ->
            NodeKind.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
        }
        val category = call.arg(CATEGORY)?.let { raw ->
            NodeTypeRegistry.all.map { it.category }.distinct()
                .firstOrNull { it.name.equals(raw, ignoreCase = true) || it.displayName.equals(raw, ignoreCase = true) }
        }
        return NodeCatalog.index(kind, category, call.arg(SEARCH))
            .ifBlank { "Nothing matched. Call list_node_types with no filters to see everything." }
    }

    /**
     * What the Problems panel would say, in the model's terms.
     *
     * The English sentence rather than the `IssueReason`, which is what the *panel*
     * words — that wording lives in `feature/` and this text is read by a model, the
     * same choice the run log already makes.
     */
    private fun describeProblems(workflow: Workflow): String {
        val validation = GraphValidator(workflow).validate()
        if (validation.isEmpty) return "No problems. The workflow is ready to run."
        return buildString {
            appendLine("Problems found:")
            validation.issues.forEach { issue ->
                val where = issue.nodes.joinToString { it.value }.ifBlank { issue.connectionId.orEmpty() }
                val label = if (issue.severity == Severity.ERROR) "ERROR" else "warning"
                appendLine("  $label: ${issue.message}${if (where.isBlank()) "" else " [$where]"}")
            }
            if (validation.errors.isEmpty()) {
                appendLine("Nothing here stops the workflow running; warnings are usually for the user to resolve.")
            }
        }.trimEnd()
    }

    // endregion

    // region Writing

    private fun addNode(workflow: Workflow, call: AiToolCall, nameOf: (NodeTypeDefinition) -> String): GraphEdit {
        val raw = call.required(TYPE_ID) ?: return workflow.fails(missing(TYPE_ID))
        val typeId = NodeTypeId(raw)
        val definition = NodeTypeRegistry.byId(typeId)
            ?: return workflow.fails("There is no node type called $raw. Use list_node_types to find the right one.")
        val (x, y) = GraphLayout.provisionalPosition(workflow)
        val node = WorkflowNode(
            id = randomNodeId(),
            typeId = typeId,
            name = call.arg(NAME)?.takeIf { it.isNotBlank() } ?: nameOf(definition),
            x = x,
            y = y,
            config = initialConfigFor(typeId),
        )
        val needsUser = NodeCatalog.userChosenFields(typeId)
        val note = if (needsUser.isEmpty()) {
            ""
        } else {
            " The user must choose these on the node itself: ${needsUser.joinToString { it.value }}."
        }
        return workflow.copy(nodes = workflow.nodes + node).answers(
            "Added ${definition.displayName} as ${node.id.value}.$note",
            added = setOf(node.id),
        )
    }

    private fun deleteNode(workflow: Workflow, call: AiToolCall): GraphEdit {
        val id = call.required(NODE_ID) ?: return workflow.fails(missing(NODE_ID))
        val node = workflow.node(NodeId(id)) ?: return workflow.fails(noSuchNode(id))
        return workflow.withoutNode(node.id).answers("Deleted ${node.name}.")
    }

    /**
     * Puts a node somewhere, which is the one edit that changes nothing about what the
     * workflow *does*.
     *
     * The coordinates are bounded rather than trusted. A model that answers `1e9` would
     * leave the card somewhere the canvas cannot practically be panned to, and the node
     * would be gone as far as anybody could tell — which is a worse outcome than being
     * told the number was refused.
     */
    private fun moveNode(workflow: Workflow, call: AiToolCall): GraphEdit {
        val id = call.required(NODE_ID) ?: return workflow.fails(missing(NODE_ID))
        val node = workflow.node(NodeId(id)) ?: return workflow.fails(noSuchNode(id))
        val x = call.coordinate(X) ?: return workflow.fails(badCoordinate(X))
        val y = call.coordinate(Y) ?: return workflow.fails(badCoordinate(Y))
        return workflow.withNodeAt(node.id, x, y)
            .answers("Moved ${node.name}.", moved = setOf(node.id), touched = setOf(node.id))
    }

    private fun setConfig(workflow: Workflow, call: AiToolCall): GraphEdit {
        val id = call.required(NODE_ID) ?: return workflow.fails(missing(NODE_ID))
        val key = call.required(KEY) ?: return workflow.fails(missing(KEY))
        val value = call.arg(VALUE).orEmpty()
        val node = workflow.node(NodeId(id)) ?: return workflow.fails(noSuchNode(id))
        val configKey = ConfigKey(key)
        if (configKey in NodeCatalog.userChosenFields(node.typeId)) {
            return workflow.fails(
                "\"$key\" names something from the user's own library and cannot be typed in. " +
                    "Leave it blank and tell the user to pick it on the ${node.name} card.",
            )
        }
        val parameters = NodeToolCatalog.parametersFor(node.typeId)
        val known = NodeCatalog.settableKeys(node.typeId)
        if (configKey !in known) {
            return workflow.fails(
                "${node.typeId.value} has no config field called \"$key\". " +
                    "It has: ${known.joinToString { it.value }}.",
            )
        }
        parameters.firstOrNull { it.name == key }?.let { parameter ->
            problemWith(parameter.schema, value)?.let { return workflow.fails("\"$key\" $it") }
        }
        return workflow.withConfig(node.id, configKey, value)
            .answers("Set $key on ${node.name}.", touched = setOf(node.id))
    }

    /**
     * Wires two ports, working out for itself which channel they are on.
     *
     * The model is not asked whether this is an execution or a data wire: the ports
     * already know, and a field asking it to say would be a field it could contradict.
     */
    private fun connect(workflow: Workflow, call: AiToolCall, nameOf: (NodeTypeDefinition) -> String): GraphEdit {
        val fromId = call.required(FROM_NODE) ?: return workflow.fails(missing(FROM_NODE))
        val fromPort = call.required(FROM_PORT) ?: return workflow.fails(missing(FROM_PORT))
        val toId = call.required(TO_NODE) ?: return workflow.fails(missing(TO_NODE))
        val toPort = call.required(TO_PORT) ?: return workflow.fails(missing(TO_PORT))

        val source = workflow.outputPort(fromId, fromPort)
            ?: return workflow.fails(portProblem(workflow, fromId, fromPort, Direction.OUT))
        val target = workflow.inputPort(toId, toPort)
            ?: return workflow.fails(portProblem(workflow, toId, toPort, Direction.IN))
        if (source.kind != target.kind) {
            return workflow.fails(
                "${fromPort} is ${channel(source.kind)} and $toPort is ${channel(target.kind)}. " +
                    "Execution wires and data wires cannot be joined to each other.",
            )
        }
        val output = PortAddress(NodeId(fromId), source.name, source.kind, isOutput = true)
        val input = PortAddress(NodeId(toId), target.name, target.kind, isOutput = false)
        val touched = setOf(output.nodeId, input.nodeId)
        if (workflow.connectionExists(output, input)) {
            return workflow.answers("Those ports are already wired together.", touched = touched)
        }
        if (source.kind == PortKind.DATA && !workflow.isTypeCompatible(output, input)) {
            val autocast = workflow.withAutocast(
                output = output,
                input = input,
                place = { GraphLayout.provisionalPosition(workflow) },
                nameOf = nameOf,
            ) ?: return workflow.fails(
                "A ${typeOf(source)} cannot become a ${typeOf(target)}, so those ports cannot be wired together.",
            )
            return autocast.workflow.withRevealedInput(input).answers(
                "Wired, with a Convert node in between because a ${typeOf(source)} is not a ${typeOf(target)}.",
                added = setOf(autocast.convertId),
                touched = touched + autocast.convertId,
            )
        }
        return workflow.withConnection(output, input).withRevealedInput(input)
            .answers("Wired $fromPort to $toPort.", touched = touched)
    }

    private fun disconnect(workflow: Workflow, call: AiToolCall): GraphEdit {
        val id = call.required(CONNECTION_ID) ?: return workflow.fails(missing(CONNECTION_ID))
        val exists = (workflow.execConnections.map { it.id } + workflow.dataConnections.map { it.id }).contains(id)
        if (!exists) return workflow.fails("There is no wire with id $id. Call read_graph for the current ids.")
        return workflow.withoutConnection(id).answers("Removed that wire.")
    }

    private fun declareVariable(workflow: Workflow, call: AiToolCall): GraphEdit {
        val name = call.required(NAME) ?: return workflow.fails(missing(NAME))
        val rawType = call.arg(TYPE).orEmpty()
        val type = when {
            rawType.isBlank() -> ValueType.TEXT
            else -> ValueType.entries.firstOrNull { it.name.equals(rawType, ignoreCase = true) }
                ?: return workflow.fails(
                    "\"$rawType\" is not a variable type. Use one of: " +
                        ValueType.entries.joinToString { it.name } + ".",
                )
        }
        workflow.variables.firstOrNull { it.name.equals(name, ignoreCase = true) }?.let {
            return workflow.answers("There is already a variable called ${it.name}; its id is ${it.id}.")
        }
        val declaration = VariableDeclaration(
            id = UUID.randomUUID().toString(),
            name = name,
            type = type,
            initialValue = call.arg(INITIAL_VALUE).orEmpty(),
        )
        return workflow.copy(variables = workflow.variables + declaration)
            .answers("Declared ${declaration.name} as ${declaration.id}.")
    }

    private fun deleteVariable(workflow: Workflow, call: AiToolCall): GraphEdit {
        val id = call.required(VARIABLE_ID) ?: return workflow.fails(missing(VARIABLE_ID))
        val declaration = workflow.variable(id)
            ?: return workflow.fails("This workflow has no variable with id $id.")
        return workflow.copy(variables = workflow.variables - declaration)
            .answers("Deleted the variable ${declaration.name}.")
    }

    private fun setMacro(workflow: Workflow, call: AiToolCall): GraphEdit {
        val name = call.arg(NAME)?.takeIf { it.isNotBlank() }
        val rawIcon = call.arg(ICON)?.takeIf { it.isNotBlank() }
        val rawAccent = call.arg(ACCENT)?.takeIf { it.isNotBlank() }
        val icon = rawIcon?.let { raw ->
            MacroIcon.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
                ?: return workflow.fails(
                    "\"$raw\" is not an icon. Use one of: " + MacroIcon.entries.joinToString { it.name } + ".",
                )
        }
        val accent = rawAccent?.let { raw ->
            MacroAccent.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
                ?: return workflow.fails(
                    "\"$raw\" is not a colour. Use one of: " + MacroAccent.entries.joinToString { it.name } + ".",
                )
        }
        if (name == null && icon == null && accent == null) {
            return workflow.fails("set_macro was given nothing to change.")
        }
        return workflow.copy(
            name = name ?: workflow.name,
            icon = icon ?: workflow.icon,
            accent = accent ?: workflow.accent,
        ).answers("Updated the macro.")
    }

    // endregion

    // region Shared

    private fun Workflow.outputPort(nodeId: String, portName: String): Port? {
        val node = node(NodeId(nodeId)) ?: return null
        val definition = NodeTypeRegistry.byId(node.typeId) ?: return null
        return effectiveOutputPorts(definition, this, node).firstOrNull { it.name.value == portName }
    }

    private fun Workflow.inputPort(nodeId: String, portName: String): Port? {
        val node = node(NodeId(nodeId)) ?: return null
        val definition = NodeTypeRegistry.byId(node.typeId) ?: return null
        return effectiveInputPorts(definition, this, node).firstOrNull { it.name.value == portName }
    }

    /**
     * Why a port could not be resolved, naming the ones that exist.
     *
     * The single most valuable error message here: a model that guessed `output` when
     * the port is called `out` fixes itself on the next turn if it is told what is
     * actually there, and spends the rest of the turn confused if it is not.
     */
    private fun portProblem(workflow: Workflow, nodeId: String, portName: String, direction: Direction): String {
        val node = workflow.node(NodeId(nodeId)) ?: return noSuchNode(nodeId)
        val definition = NodeTypeRegistry.byId(node.typeId)
            ?: return "${node.name} is a node type this build does not have."
        val available = when (direction) {
            Direction.OUT -> effectiveOutputPorts(definition, workflow, node)
            Direction.IN -> effectiveInputPorts(definition, workflow, node)
        }
        val side = if (direction == Direction.OUT) "output" else "input"
        return "${node.name} (${node.typeId.value}) has no $side port called \"$portName\". " +
            "Its $side ports are: " + available.joinToString { "${it.name.value} (${channel(it.kind)})" } + "."
    }

    private fun channel(kind: PortKind): String = if (kind == PortKind.EXECUTION) "an execution port" else "a data port"

    private fun typeOf(port: Port): String = port.schema?.let { NodeCatalog.typeNameOf(it) } ?: "pulse"

    /** Why [value] does not fit [schema], or null when it does. */
    private fun problemWith(schema: AiParamSchema, value: String): String? {
        if (value.isBlank()) return null
        return when (schema) {
            is AiParamSchema.Text -> when {
                schema.options.isEmpty() || value in schema.options -> null
                else -> "must be one of: ${schema.options.joinToString()}."
            }
            AiParamSchema.Integer -> if (value.toIntOrNull() == null) "must be a whole number." else null
            AiParamSchema.Decimal -> if (value.toDoubleOrNull() == null) "must be a number." else null
            AiParamSchema.Flag -> if (value !in TRUTH_VALUES) "must be true or false." else null
            is AiParamSchema.Items -> null
        }
    }

    private val TRUTH_VALUES = setOf("true", "false")

    private fun AiToolCall.arg(name: String): String? = arguments[name]

    private fun AiToolCall.required(name: String): String? = arguments[name]?.takeIf { it.isNotBlank() }

    /** A place on the canvas, or null when it is not a number this canvas can hold. */
    private fun AiToolCall.coordinate(name: String): Float? =
        arguments[name]?.trim()?.toFloatOrNull()?.takeIf { it.isFinite() && abs(it) <= MAX_COORDINATE }

    private fun badCoordinate(name: String): String =
        "\"$name\" must be a number between -${MAX_COORDINATE.toInt()} and ${MAX_COORDINATE.toInt()}."

    private fun missing(name: String): String = "This tool needs a $name."

    private fun noSuchNode(id: String): String =
        "There is no node with id $id in this workflow. Call read_graph for the current ids."

    private fun Workflow.answers(
        text: String,
        added: Set<NodeId> = emptySet(),
        moved: Set<NodeId> = emptySet(),
        touched: Set<NodeId> = emptySet(),
    ): GraphEdit = GraphEdit(this, AiToolResult(text), added, moved, touched + added)

    /** The graph is returned unchanged: a tool that failed must not half-edit it. */
    private fun Workflow.fails(text: String): GraphEdit = GraphEdit(this, AiToolResult(text, isError = true))

    /** Config values are free text and a notification body is ordinary; keep the log readable. */
    private fun String.oneLine(): String = replace('\n', ' ').take(MAX_CONFIG_ECHO)

    private const val MAX_CONFIG_ECHO = 200

    /**
     * How far from the origin a node may be put.
     *
     * Far enough that no honest layout reaches it — the whole palette laid out in one
     * column would not — and near enough that a card at the limit can still be found by
     * fitting the canvas to its contents.
     */
    private const val MAX_COORDINATE = 100_000f

    // endregion
}
