package com.example.ottomatic.engine.validation

import com.example.ottomatic.core.model.ConfigKey
import com.example.ottomatic.core.model.NodeId
import com.example.ottomatic.core.model.PortName
import com.example.ottomatic.domain.model.DataConnection
import com.example.ottomatic.domain.model.Direction
import com.example.ottomatic.domain.model.ExecConnection
import com.example.ottomatic.domain.model.ExecPorts
import com.example.ottomatic.domain.model.NodeKind
import com.example.ottomatic.domain.model.NodeTypeDefinition
import com.example.ottomatic.domain.model.Port
import com.example.ottomatic.domain.model.PortKind
import com.example.ottomatic.domain.model.Workflow
import com.example.ottomatic.domain.model.WorkflowNode
import com.example.ottomatic.domain.model.schema.ItemSchema
import com.example.ottomatic.domain.registry.GrantedPrerequisites
import com.example.ottomatic.domain.model.ToolSpec
import com.example.ottomatic.domain.model.ToolTarget
import com.example.ottomatic.domain.registry.ConfigFieldType
import com.example.ottomatic.domain.registry.ConfigSchemaRegistry
import com.example.ottomatic.domain.registry.MacroDirectory
import com.example.ottomatic.domain.registry.NodeTypeRegistry
import com.example.ottomatic.domain.registry.PluginNodes
import com.example.ottomatic.domain.registry.declarationFor
import com.example.ottomatic.nodeapi.plugin.PluginLimits
import com.example.ottomatic.domain.model.hubIdOf
import com.example.ottomatic.domain.registry.AiConnections
import com.example.ottomatic.domain.registry.SmartHomeHubs
import com.example.ottomatic.domain.registry.aiConnectionRefKeys
import com.example.ottomatic.domain.registry.macroRefKeys
import com.example.ottomatic.domain.registry.toolListKeys
import com.example.ottomatic.domain.registry.isOptionalPicker
import com.example.ottomatic.domain.registry.smartHomeRefFields
import com.example.ottomatic.domain.registry.effectivePort
import com.example.ottomatic.domain.registry.isDataAssignable
import com.example.ottomatic.domain.registry.variableRefKeys

/**
 * One finding produced by validating a [Workflow] graph.
 *
 * A finding says two separate things, and both are needed:
 *
 *  - **where it is** — [nodes] are the cards to badge and [connectionId] the edge
 *    to colour, so the editor can point at the problem instead of describing it;
 *  - **what it costs** — [blockedNodes] and [blockedConnections] are what must not
 *    run because of it. An [Severity.ERROR] blocks the smallest thing that is
 *    actually broken; a [Severity.WARNING] blocks nothing at all, by construction.
 *
 * The quarantine is decided here rather than in [GraphValidation] because working
 * it out needs the graph — resolving a broken data edge to the actions that
 * eventually read it means walking the transform chain — and by the time the
 * findings are aggregated the [Workflow] is gone.
 *
 * [nodes] iteration order is meaningful: it is the order the problems list renders
 * (a cycle reads as its path), and the first entry is what tapping the row selects.
 */
data class ValidationIssue(
    val severity: Severity,
    /**
     * The English sentence.
     *
     * Still here, and still built in the engine, because it is what `executeFrom`
     * writes to the run log — which is not translated. The Problems panel ignores it
     * and renders [reason] instead; the two must therefore say the same thing.
     */
    val message: String,
    val nodes: Set<NodeId> = emptySet(),
    val connectionId: String? = null,
    val blockedNodes: Set<NodeId> = emptySet(),
    val blockedConnections: Set<String> = emptySet(),
    /** What this is about, for the panel to word. Null falls back to [message]. */
    val reason: IssueReason? = null,
    /** The values that fill [reason]'s blanks, in the order its KDoc names them. */
    val args: List<String> = emptyList(),
)

enum class Severity { ERROR, WARNING }

/**
 * Validates a [Workflow] graph against the two-channel (execution + data)
 * model: port existence and kind/direction sanity, acyclicity of both edge
 * sets, structural schema subtyping on data edges, and the strict data
 * semantics rule (a data edge's source must be exec-upstream of its target).
 *
 * Run continuously by the editor — which is where the result is *shown* — and
 * again by [com.example.ottomatic.engine.WorkflowExecutor] against the snapshot it
 * is about to run, which is where the result is *enforced*. Neither trusts the
 * other: the editor's graph is unsaved, and the executor's may have been armed
 * before the editor existed, or hand-edited on disk.
 *
 * It never rejects a whole workflow. Every finding names what it blocks, and the
 * executor skips exactly that, so one broken wire costs one branch rather than
 * every branch under every trigger.
 */
@Suppress("TooManyFunctions")
class GraphValidator(private val workflow: Workflow) {

    fun validate(): GraphValidation {
        val issues = mutableListOf<ValidationIssue>()
        validateNodeTypes(issues)
        validateExecConnections(issues)
        validateDataConnections(issues)
        validateExecAcyclicity(issues)
        validateDataAcyclicity(issues)
        validateStrictDataSemantics(issues)
        validateForkBranches(issues)
        validateValueNodesAreUsed(issues)
        validateTriggers(issues)
        validateLoopBodies(issues)
        validateVariableRefs(issues)
        validateMacroRefs(issues)
        validateSmartHomeRefs(issues)
        validateAiConnectionRefs(issues)
        validateAiToolRefs(issues)
        validatePrerequisites(issues)
        validatePluginPermissions(issues)
        return GraphValidation(issues)
    }

    /**
     * A node needing a permission the user has not granted.
     *
     * The same family and the same stance as [validateMacroRefs] and
     * [validateVariableRefs]: a warning that blocks nothing. The graph is
     * structurally perfect — this is a fact about the phone, not about the wiring
     * — and quarantining the node would take out work that starts running the
     * moment a switch is flipped in Settings, with no edit here at all.
     *
     * What it adds is *reach*. The node's config form has always shown this, but
     * only to somebody who opened that node, and a macro missing a grant is
     * exactly the one that looks fine from the outside: the geofence that is never
     * registered, the Launch App that Android drops because the app is in the
     * background. Both fail silently and both look identical to a macro that is
     * simply waiting. Here it lands in the Problems panel and on the workflow
     * list's count, where it is visible without knowing to go looking.
     *
     * The [GrantedPrerequisites.isHydrated] guard is the same one
     * [validateMacroRefs] needs, and matters more: unhydrated, this would badge
     * every permission-declaring node in every macro.
     *
     * Only *declared* prerequisites are covered. `action.call`'s contacts access
     * is derived from its config rather than its type — see `usesContacts` — so it
     * stays a card on the node, where the config it depends on is in view.
     */
    private fun validatePrerequisites(out: MutableList<ValidationIssue>) {
        if (!GrantedPrerequisites.isHydrated) return
        for (node in workflow.nodes) {
            val missing = NodeTypeRegistry.byId(node.typeId)
                ?.permissionRequirements
                .orEmpty()
                .distinctBy { it.key }
                .filterNot { GrantedPrerequisites.isSatisfied(it) }
            if (missing.isEmpty()) continue
            val needs = missing.joinToString(" and ") { it.label }
            out += ValidationIssue(
                Severity.WARNING,
                "'${node.name}' needs $needs, which has not been granted — it may do nothing when it runs",
                reason = IssueReason.PERMISSION_MISSING,
                args = listOf(node.name, needs),
                nodes = setOf(node.id),
            )
        }
    }

    /**
     * A plugin node whose *own app* is missing a permission it says it needs.
     *
     * The third member of the [validateVariableRefs] / [validateMacroRefs] /
     * [validatePrerequisites] family, and it takes the last of those stances exactly:
     * a WARNING that blocks nothing, because this is a fact about the phone rather than
     * about the wiring — the graph is perfect, and granting the permission in Settings
     * starts it working with no edit here at all.
     *
     * The sentence names the plugin, because that is where the user has to go: the
     * permission belongs to *that* app, is granted on *that* app's settings page, and
     * Ottomatic cannot request it or hold it on the plugin's behalf. Saying "'Scan QR'
     * needs camera access" without saying whose would send people to the wrong screen.
     */
    private fun validatePluginPermissions(out: MutableList<ValidationIssue>) {
        if (!PluginNodes.isHydrated) return
        for (node in workflow.nodes) {
            val entry = PluginNodes.byId(node.typeId)?.takeIf { it.missingPermissions.isNotEmpty() } ?: continue
            val needs = entry.missingPermissions.joinToString(" and ") { it.substringAfterLast('.') }
            out += ValidationIssue(
                Severity.WARNING,
                "'${node.name}' needs $needs, which ${entry.pluginName} has not been granted — " +
                    "it may do nothing when it runs",
                reason = IssueReason.PLUGIN_PERMISSION_MISSING,
                args = listOf(node.name, needs, entry.pluginName),
                nodes = setOf(node.id),
            )
        }
    }

    /**
     * A node that names no macro, or one whose workflow has been deleted.
     *
     * The same family and the same stance as [validateVariableRefs]: a warning that
     * blocks nothing, because `MacroControl.enable` already returns false, the node
     * already reports `changed = false` and pulses `out`, and until now nothing
     * anywhere said why.
     *
     * The [MacroDirectory.isHydrated] guard is the one line worth reading twice.
     * Nothing has to have listed the workflows for this to run — the executor
     * validates a disk snapshot on boot — and an unhydrated directory answers "not
     * found" to everything, so without the guard every macro reference in the graph
     * would be reported as dangling. Empty and unasked are different states.
     */
    private fun validateMacroRefs(out: MutableList<ValidationIssue>) {
        for (node in workflow.nodes) {
            for (key in macroRefKeys(node.typeId)) {
                val spec = node.config[key].orEmpty()
                val (reason, message) = when {
                    spec.isBlank() ->
                        IssueReason.MACRO_UNSET to "'${node.name}' has no macro chosen, so it will do nothing"
                    MacroDirectory.isHydrated && MacroDirectory.byId(spec) == null ->
                        IssueReason.MACRO_MISSING to "'${node.name}' points at a macro that no longer exists"
                    else -> continue
                }
                out += ValidationIssue(
                    Severity.WARNING,
                    message,
                    nodes = setOf(node.id),
                    reason = reason,
                    args = listOf(node.name),
                )
            }
        }
    }

    /**
     * A light node that names nothing, or whose hub has been removed.
     *
     * The third member of the [validateVariableRefs] / [validateMacroRefs] family,
     * with the same stance — a warning that blocks nothing, because the node already
     * reports the failure on its `state` port and pulses `out`.
     *
     * The argument for it is stronger than for either of the others, and the reason
     * is the thing that makes the target spec good: it caches the name it was given,
     * so a node pointing at a deleted hub renders **perfectly**. The field still
     * reads "Kitchen ceiling". Nothing on the canvas is wrong, and nothing happens.
     * That is precisely the failure the Problems panel exists to surface.
     *
     * A reference that will not parse is deliberately *not* reported here: a wired
     * field can hold anything at design time, and the node names what it read when
     * it runs, which is more use than a warning that cannot see the value the wire
     * will carry.
     */
    private fun validateSmartHomeRefs(out: MutableList<ValidationIssue>) {
        for (node in workflow.nodes) {
            // An *optional* picker's blank is a real answer rather than an unfinished one, so
            // it is dropped before anything is asked of it. `action.ha_service`'s entity is the
            // case: a service that acts on nothing takes no entity, and every such node was
            // being badged for saying so.
            val fields = smartHomeRefFields(node.typeId)
                .filterNot { it.isOptionalPicker && node.config[it.key].isNullOrBlank() }
            for (field in fields) {
                val spec = node.config[field.key].orEmpty()
                // Either spelling: a light reference or a Home Assistant one. See hubIdOf.
                val hubId = hubIdOf(spec)
                val (reason, message) = when {
                    spec.isBlank() ->
                        IssueReason.HUB_UNSET to "'${node.name}' has nothing chosen, so it will do nothing"
                    hubId != null && SmartHomeHubs.isHydrated && !SmartHomeHubs.exists(hubId) ->
                        IssueReason.HUB_MISSING to "'${node.name}' points at a hub that is no longer set up"
                    else -> continue
                }
                out += ValidationIssue(
                    Severity.WARNING,
                    message,
                    nodes = setOf(node.id),
                    reason = reason,
                    args = listOf(node.name),
                )
            }
        }
    }

    /**
     * An AI node that names no connection, or one that has been deleted.
     *
     * The fourth member of the [validateVariableRefs] / [validateMacroRefs] /
     * [validateSmartHomeRefs] family, with the same stance — a warning that blocks
     * nothing, because the node already reports the failure in the run log and
     * pulses `out` onto its fallback.
     *
     * The unset case is the one that earns it here. Every other field on `Ask AI`
     * has a sensible default and a freshly dropped node looks complete, so "no
     * connection chosen" is easy to miss until a macro runs at three in the morning
     * and answers nothing — which is exactly the failure the whole AI credential
     * design is arranged around.
     */
    private fun validateAiConnectionRefs(out: MutableList<ValidationIssue>) {
        for (node in workflow.nodes) {
            for (key in aiConnectionRefKeys(node.typeId)) {
                aiConnectionProblem(node, node.config[key].orEmpty())?.let { (reason, message) ->
                    out += ValidationIssue(
                        Severity.WARNING,
                        message,
                        nodes = setOf(node.id),
                        reason = reason,
                        args = listOf(node.name),
                    )
                }
            }
        }
    }

    /**
     * What is wrong with [node]'s AI connection, or null when nothing is.
     *
     * The three cases get three sentences because they have three different fixes:
     * pick a connection, point somewhere that still exists, or finish setting the
     * one you have up. The third arrived with the open-ended providers and is the
     * one that most needs saying — a self-hosted connection with no server address
     * renders perfectly in the picker, name and provider and key, and answers
     * nothing.
     */
    private fun aiConnectionProblem(node: WorkflowNode, id: String): Pair<IssueReason, String>? = when {
        id.isBlank() ->
            IssueReason.AI_UNSET to "'${node.name}' has no AI connection chosen, so it will do nothing"
        !AiConnections.isHydrated -> null
        !AiConnections.exists(id) ->
            IssueReason.AI_MISSING to "'${node.name}' points at an AI connection that no longer exists"
        !AiConnections.isConfigured(id) ->
            IssueReason.AI_UNFINISHED to
                "'${node.name}' points at an AI connection that is not finished being set up"
        else -> null
    }

    /**
     * A tool an AI node offers that will not work.
     *
     * The fourth member of the [validateVariableRefs] / [validateMacroRefs] /
     * [validateSmartHomeRefs] family, with the same stance — a warning that blocks
     * nothing, because the catalogue already drops an unusable tool rather than
     * offering it, and a node with one fewer tool still runs.
     *
     * Two things are reported, and the second is the one that could not be caught
     * anywhere else:
     *
     * - A tool naming a **node type or macro that is gone**. Nothing on the canvas
     *   says so — the row keeps rendering — and the only symptom is a model that
     *   cannot do something it used to.
     * - A tool with an **unpinned picker field**. That field holds an identifier a
     *   model cannot invent, so the tool will run with the field at its default and
     *   name nothing. This is the exact failure `@Picker` exists to prevent in a
     *   form, reappearing one level out, and the run log cannot catch it because
     *   nothing *fails* — the node runs and does nothing.
     *
     * A missing *grant* is deliberately not reported here: [validatePrerequisites]
     * already walks every node's declared permissions, and a tool is a node type, so
     * a second report would badge the same fact twice with different words.
     */
    private fun validateAiToolRefs(out: MutableList<ValidationIssue>) {
        for (node in workflow.nodes) {
            val specs = toolListKeys(node.typeId).flatMap { key -> ToolSpec.parse(node.config[key]) }
            for (problem in specs.mapNotNull { spec -> toolProblem(node, spec) }) {
                out += ValidationIssue(
                    Severity.WARNING,
                    problem.message,
                    nodes = setOf(node.id),
                    reason = problem.reason,
                    args = problem.args,
                )
            }
        }
    }

    /**
     * What is wrong with one tool, or null when nothing is.
     *
     * The English [ValidationIssue.message] and the panel's resource are built from
     * the same [args], so the two cannot drift — which is the rule the whole
     * [IssueReason] mechanism exists to keep.
     */
    @Suppress("ReturnCount") // One exit per distinct problem, each with its own sentence.
    private fun toolProblem(node: WorkflowNode, spec: ToolSpec): ToolProblem? {
        val target = spec.target
        if (target is ToolTarget.Macro) {
            if (!MacroDirectory.isHydrated || MacroDirectory.byId(target.macroId) != null) return null
            return missingTool(node, MacroDirectory.byId(target.macroId)?.name ?: target.macroId)
        }
        val typeId = (target as ToolTarget.Node).typeId
        val definition = NodeTypeRegistry.byId(typeId) ?: return missingTool(node, typeId.value)
        val loose = ConfigSchemaRegistry.byId(typeId)?.fields.orEmpty()
            .filter { it.type is ConfigFieldType.PICKER && it.key !in spec.pinned }
        if (loose.isEmpty()) return null
        val fields = loose.joinToString(" and ") { it.label }
        return ToolProblem(
            reason = IssueReason.AI_TOOL_UNPINNED,
            message = "'${node.name}' offers '${definition.displayName}' without choosing its " +
                "$fields, which the AI cannot fill in",
            args = listOf(node.name, definition.displayName, fields),
        )
    }

    private fun missingTool(node: WorkflowNode, tool: String) = ToolProblem(
        reason = IssueReason.AI_TOOL_MISSING,
        message = "'${node.name}' offers the AI '$tool', which is no longer available",
        args = listOf(node.name, tool),
    )

    private data class ToolProblem(
        val reason: IssueReason,
        val message: String,
        val args: List<String>,
    )

    /**
     * A node that names no variable, or one whose declaration has been deleted.
     *
     * Both are warnings and both block nothing. The node degrades exactly as it
     * always has — it logs that it stored nothing and pulses `out` — the rest of the
     * graph is structurally sound, and quarantining an action because one of its
     * fields is unset would take out work the user can see is otherwise fine. Same
     * family as "'X' is not wired to anything".
     *
     * This is deliberately not the stance a broken *data edge* gets. There, falling
     * back would substitute a form value for a wire drawn on the canvas, which is a
     * lie about what the graph says; here there is no wire and nothing is
     * substituted, only a step that does nothing and says so.
     */
    private fun validateVariableRefs(out: MutableList<ValidationIssue>) {
        for (node in workflow.nodes) {
            for (key in variableRefKeys(node.typeId)) {
                val spec = node.config[key].orEmpty()
                val (reason, message) = when {
                    spec.isBlank() ->
                        IssueReason.VARIABLE_UNSET to "'${node.name}' has no variable chosen, so it will do nothing"
                    declarationFor(workflow, spec) == null ->
                        IssueReason.VARIABLE_MISSING to "'${node.name}' uses a variable that no longer exists"
                    else -> continue
                }
                out += ValidationIssue(
                    Severity.WARNING,
                    message,
                    nodes = setOf(node.id),
                    reason = reason,
                    args = listOf(node.name),
                )
            }
        }
    }

    /**
     * Whether this node is a loop, i.e. declares `body` as an **exec output**.
     *
     * The kind and direction are load-bearing, not decoration. [NodeTypeDefinition.port]
     * matches by name alone, and a port name is only unique per kind and direction —
     * so `action.send_sms` and `action.http`, whose `@Wired` message property is
     * called `body`, each have a DATA *input* of that name and were both being
     * reported as loops with nothing in their body.
     */
    private fun WorkflowNode.isLoop(): Boolean =
        NodeTypeRegistry.byId(typeId)
            ?.outputs(PortKind.EXECUTION)
            .orEmpty()
            .any { it.name == ExecPorts.BODY }

    /**
     * Whether this node is a fork, i.e. declares `resumed` as an **exec output** —
     * derived from the declaration for the reason [isLoop] is, so a second fork
     * node needs nothing registered here.
     */
    private fun WorkflowNode.isFork(): Boolean =
        NodeTypeRegistry.byId(typeId)
            ?.outputs(PortKind.EXECUTION)
            .orEmpty()
            .any { it.name == ExecPorts.RESUMED }

    /**
     * A loop whose `body` output goes nowhere walks its list and does nothing with
     * it. Structurally fine — `completed` may well be wired — so it is a warning in
     * the same family as an unwired trigger: the graph works, it just cannot do
     * what the node is for yet.
     */
    private fun validateLoopBodies(out: MutableList<ValidationIssue>) {
        val emptyLoops = workflow.nodes
            .filter { it.isLoop() }
            .filter { workflow.outgoingExec(it.id, ExecPorts.BODY).isEmpty() }
        for (node in emptyLoops) {
            out += ValidationIssue(
                Severity.WARNING,
                "'${node.name}' has nothing in its loop body, so repeating does nothing",
                reason = IssueReason.EMPTY_LOOP_BODY,
                args = listOf(node.name),
                nodes = setOf(node.id),
            )
        }
    }

    /**
     * A node whose type is not in the registry. The executor's `ActionRegistry.byId(…)
     * ?: continue` skips it in total silence, so a workflow saved by a newer build —
     * or one naming a node type since removed — presents as "my macro does nothing"
     * with no diagnostic anywhere. Naming it is most of the value of validating at all.
     */
    /**
     * Whether this typeId is one we can say anything about yet.
     *
     * A plugin's nodes arrive asynchronously, some way after process start. Without the
     * second clause, every boot-time snapshot validation would condemn every plugin node
     * in every macro on the device for the seconds before discovery finished — the exact
     * mistake `MacroDirectory` guards against, arriving by a new route. Once hydrated, an
     * uninstalled plugin's typeId falls through to the named ERROR, which quarantines
     * that node and nothing else.
     */
    private fun isKnownType(typeId: com.example.ottomatic.core.model.NodeTypeId): Boolean =
        NodeTypeRegistry.byId(typeId) != null ||
            (PluginLimits.isPluginTypeId(typeId.value) && !PluginNodes.isHydrated)

    private fun validateNodeTypes(out: MutableList<ValidationIssue>) {
        for (node in workflow.nodes) {
            if (isKnownType(node.typeId)) continue
            out += ValidationIssue(
                Severity.ERROR,
                "'${node.name}' is an unknown node type (${node.typeId.value}) and cannot run",
                reason = IssueReason.UNKNOWN_NODE_TYPE,
                args = listOf(node.name, node.typeId.value),
                nodes = setOf(node.id),
                blockedNodes = setOf(node.id),
            )
        }
    }

    /**
     * A trigger is what starts a run, so a graph with none can only ever be run from
     * the editor's Run button — and one wired to nothing fires into the void. Both
     * are warnings: the graph is well-formed, it just cannot do anything yet, which
     * is the ordinary state of a workflow halfway through being built.
     */
    private fun validateTriggers(out: MutableList<ValidationIssue>) {
        if (workflow.nodes.isEmpty()) return
        val triggers = workflow.nodes.filter { NodeTypeRegistry.byId(it.typeId)?.kind == NodeKind.TRIGGER }
        if (triggers.isEmpty()) {
            out += ValidationIssue(
                Severity.WARNING,
                "This workflow has no trigger, so nothing will ever start it",
                reason = IssueReason.NO_TRIGGER,
            )
            return
        }
        for (trigger in triggers) {
            if (workflow.outgoingExec(trigger.id).isNotEmpty()) continue
            out += ValidationIssue(
                Severity.WARNING,
                "'${trigger.name}' is not wired to anything, so it will fire and do nothing",
                reason = IssueReason.TRIGGER_NOT_WIRED,
                args = listOf(trigger.name),
                nodes = setOf(trigger.id),
            )
        }
    }

    /**
     * A broken exec edge blocks the edge and nothing else: the target may still be
     * perfectly reachable down another wire, and refusing to run it because *one*
     * of its inbound edges is malformed would quarantine work that is fine.
     */
    private fun validateExecConnections(out: MutableList<ValidationIssue>) {
        for (conn in workflow.execConnections) {
            val ends = conn.endpoints()
            if (ends == null) {
                out += ValidationIssue(
                    Severity.ERROR,
                    "An execution wire points at a node that is not here any more",
                    reason = IssueReason.EXEC_WIRE_DANGLING,
                    nodes = setOf(conn.fromNodeId, conn.toNodeId),
                    connectionId = conn.id,
                    blockedConnections = setOf(conn.id),
                )
                continue
            }
            val (from, to) = ends
            if (from == null) {
                out += ValidationIssue(
                    Severity.ERROR,
                    "Unknown exec output port '${conn.fromPort}' on ${conn.fromNodeId}",
                    reason = IssueReason.EXEC_OUTPUT_UNKNOWN,
                    args = listOf(conn.fromPort.value, nameOf(conn.fromNodeId)),
                    nodes = setOf(conn.fromNodeId, conn.toNodeId),
                    connectionId = conn.id,
                    blockedConnections = setOf(conn.id),
                )
            }
            if (to == null) {
                out += ValidationIssue(
                    Severity.ERROR,
                    "Unknown exec input port '${conn.toPort}' on ${conn.toNodeId}",
                    reason = IssueReason.EXEC_INPUT_UNKNOWN,
                    args = listOf(conn.toPort.value, nameOf(conn.toNodeId)),
                    nodes = setOf(conn.fromNodeId, conn.toNodeId),
                    connectionId = conn.id,
                    blockedConnections = setOf(conn.id),
                )
            }
        }
    }

    /**
     * A broken *data* edge blocks the node that reads it, not just the edge.
     *
     * The alternative — drop the edge and let the consumer fall back to the form
     * value of the same property, which is what an unwired input already does —
     * would have a notification quietly send its placeholder text in place of the
     * value the user can see wired into it on the canvas. A structural error is not
     * the same as a runtime one: `transform.json_read` and `action.script` land a
     * *failed read* on a fallback the user configured for exactly that, but nobody
     * configures a fallback for a wire that cannot carry what it claims to.
     *
     * [executedConsumers] is what makes this land on the right node when the
     * consumer is itself pulled: the far end of a transform chain is what actually
     * runs, so that is what gets held back.
     */
    // LongMethod: a flat sequence of edge checks, each with one exit.
    @Suppress("CyclomaticComplexMethod", "LoopWithTooManyJumpStatements", "LongMethod")
    private fun validateDataConnections(out: MutableList<ValidationIssue>) {
        for (conn in workflow.dataConnections) {
            val fromNode = workflow.node(conn.fromNodeId)
            val toNode = workflow.node(conn.toNodeId)
            if (fromNode == null || toNode == null) {
                out += ValidationIssue(
                    Severity.ERROR,
                    "A data wire points at a node that is not here any more",
                    reason = IssueReason.DATA_WIRE_DANGLING,
                    nodes = setOf(conn.fromNodeId, conn.toNodeId),
                    connectionId = conn.id,
                    blockedNodes = executedConsumers(conn.toNodeId),
                    blockedConnections = setOf(conn.id),
                )
                continue
            }
            val fromDef = NodeTypeRegistry.byId(fromNode.typeId)
            val toDef = NodeTypeRegistry.byId(toNode.typeId)
            if (fromDef == null || toDef == null) {
                // The unknown type itself is already reported by validateNodeTypes;
                // this only stops the edge being read through a definition we lack.
                out += ValidationIssue(
                    Severity.ERROR,
                    "A data wire runs through an unknown node type",
                    reason = IssueReason.DATA_WIRE_UNKNOWN_TYPE,
                    nodes = setOf(conn.fromNodeId, conn.toNodeId),
                    connectionId = conn.id,
                    blockedNodes = executedConsumers(conn.toNodeId),
                    blockedConnections = setOf(conn.id),
                )
                continue
            }
            // Always resolve via effectivePort: dynamic-port nodes
            // (`action.break`, `action.condition`) derive ports at runtime.
            val fromPort = effectivePort(fromDef, workflow, fromNode, conn.fromPort, Direction.OUT)
                ?: fromDef.port(conn.fromPort)
            val toPort = effectivePort(toDef, workflow, toNode, conn.toPort, Direction.IN)
                ?: toDef.port(conn.toPort)
            val fromBad = fromPort == null || fromPort.kind != PortKind.DATA || fromPort.direction != Direction.OUT
            if (fromBad) {
                out += dataEdgeError(
                    conn,
                    "'${conn.fromPort}' is not a data output port on ${conn.fromNodeId}",
                    IssueReason.NOT_DATA_OUTPUT,
                    listOf(conn.fromPort.value, nameOf(conn.fromNodeId)),
                )
                continue
            }
            val toBad = toPort == null || toPort.kind != PortKind.DATA || toPort.direction != Direction.IN
            if (toBad) {
                out += dataEdgeError(
                    conn,
                    "'${conn.toPort}' is not a data input port on ${conn.toNodeId}",
                    IssueReason.NOT_DATA_INPUT,
                    listOf(conn.toPort.value, nameOf(conn.toNodeId)),
                )
                continue
            }
            if (!isDataAssignable(fromPort, toPort)) {
                val sourceSchema = fromPort.schema ?: ItemSchema.Wildcard
                val targetSchema = toPort.schema ?: ItemSchema.Wildcard
                out += dataEdgeError(
                    conn,
                    "Schema mismatch on data edge: source $sourceSchema not assignable to target $targetSchema",
                    IssueReason.SCHEMA_MISMATCH,
                    listOf(sourceSchema.toString(), targetSchema.toString()),
                )
            }
        }
    }

    private fun dataEdgeError(
        conn: DataConnection,
        message: String,
        reason: IssueReason,
        args: List<String> = emptyList(),
    ) = ValidationIssue(
        Severity.ERROR,
        message,
        reason = reason,
        args = args,
        nodes = setOf(conn.fromNodeId, conn.toNodeId),
        connectionId = conn.id,
        blockedNodes = executedConsumers(conn.toNodeId),
        blockedConnections = setOf(conn.id),
    )

    /**
     * True when [nodeId] is a placed pull-side node — a value or a transform. Both
     * are read on demand and have no exec position at all.
     */
    private fun isPullNode(nodeId: NodeId): Boolean {
        val node = workflow.node(nodeId) ?: return false
        return NodeTypeRegistry.byId(node.typeId)?.kind in PULL_KINDS
    }

    /**
     * A pull-side node is only ever read through an outgoing data edge, so one with
     * no such edge does nothing at all. That is almost always an unfinished wiring
     * rather than an intent, but it breaks nothing — hence a warning.
     *
     * A transform additionally warns when nothing feeds it *and* nothing was typed
     * into it either: with neither, it can only produce its declared defaults, which
     * is never what anyone meant. Having typed something in is the whole point of a
     * `transform.split_text` used as a list literal — one item per line in the form,
     * no edge — so warning on that would put a permanent badge on a correct graph.
     */
    private fun validateValueNodesAreUsed(out: MutableList<ValidationIssue>) {
        val consumed = workflow.dataConnections.map { it.fromNodeId }.toSet()
        val fed = workflow.dataConnections.map { it.toNodeId }.toSet()
        for (node in workflow.nodes) {
            if (!isPullNode(node.id)) continue
            val definition = NodeTypeRegistry.byId(node.typeId)
            if (node.id !in consumed) {
                out += ValidationIssue(
                    Severity.WARNING,
                    "'${node.name}' is not connected to anything and will never be read",
                    reason = IssueReason.VALUE_NOT_CONNECTED,
                    args = listOf(node.name),
                    nodes = setOf(node.id),
                )
            }
            val starved = definition?.kind == NodeKind.TRANSFORM && node.id !in fed && !node.hasTypedInput(definition)
            if (starved) {
                out += ValidationIssue(
                    Severity.WARNING,
                    "'${node.name}' has nothing wired into it and will only use its own settings",
                    reason = IssueReason.NOTHING_WIRED_IN,
                    args = listOf(node.name),
                    nodes = setOf(node.id),
                )
            }
        }
    }

    /**
     * True when something has been typed into a field this node could equally have
     * been *wired* on — a `@Wired` property, whose config key is its port name.
     *
     * That equivalence is what makes the check meaningful: the form value and the
     * edge are two ways of supplying the same input, so having one is not being
     * starved of the other.
     */
    private fun WorkflowNode.hasTypedInput(definition: NodeTypeDefinition): Boolean =
        definition.inputs(PortKind.DATA).any { port -> config[ConfigKey(port.name.value)]?.isNotBlank() == true }

    /**
     * A cycle is quarantined by blocking the one edge that closes it, which leaves
     * every node on the loop reachable and runnable exactly once. Blocking the nodes
     * instead would delete a whole chain of working steps over one wire too many.
     *
     * This report is for **attribution** — what to colour, and what to say. It is not
     * what makes running a cyclic graph *safe*: the executor carries its own
     * path-scoped guard for that, because a workflow can reach it hand-edited, or
     * having been armed before this rule existed.
     */
    private fun validateExecAcyclicity(out: MutableList<ValidationIssue>) {
        for (cycle in findCycles(workflow.execConnections) { it.fromNodeId to it.toNodeId }) {
            out += ValidationIssue(
                Severity.ERROR,
                "Execution cycle detected: ${cycle.path(::nameOf)}",
                reason = IssueReason.EXEC_CYCLE,
                args = listOf(cycle.path(::nameOf)),
                nodes = cycle.nodes.toSet(),
                connectionId = cycle.closing.id,
                blockedConnections = setOf(cycle.closing.id),
            )
        }
    }

    private fun validateDataAcyclicity(out: MutableList<ValidationIssue>) {
        for (cycle in findCycles(workflow.dataConnections) { it.fromNodeId to it.toNodeId }) {
            out += ValidationIssue(
                Severity.ERROR,
                "Data cycle detected: ${cycle.path(::nameOf)}",
                reason = IssueReason.DATA_CYCLE,
                args = listOf(cycle.path(::nameOf)),
                nodes = cycle.nodes.toSet(),
                connectionId = cycle.closing.id,
                blockedNodes = executedConsumers(cycle.closing.toNodeId),
                blockedConnections = setOf(cycle.closing.id),
            )
        }
    }

    @Suppress("ReturnCount")
    private fun validateStrictDataSemantics(out: MutableList<ValidationIssue>) {
        // For every data edge source -> target, source must be exec-upstream of target
        // (i.e. target is reachable from source by following exec edges). Otherwise the
        // source would not have run by the time the target executes.
        //
        // A pull-side node (VALUE, TRANSFORM) is exempt on *both* ends, and for the same
        // reason: it is never pulsed, so it has no exec position for "upstream" to mean
        // anything against. As a source it is read on demand while collecting the
        // target's inputs, which is always in time by construction. As a target it does
        // not execute at all — the moment that matters is when the node that eventually
        // *reads* the chain runs, so the rule is applied against [executedConsumers]
        // instead. Checking the transform itself would reject the ordinary autocast
        // shape (trigger -> Convert -> action), where nothing is ever exec-upstream of
        // the Convert node because it has no exec input to reach.
        val execForward = mutableMapOf<NodeId, MutableList<NodeId>>()
        workflow.execConnections.forEach {
            execForward.getOrPut(it.fromNodeId) { mutableListOf() } += it.toNodeId
        }
        for (conn in workflow.dataConnections) {
            if (isPullNode(conn.fromNodeId)) continue
            val unreached = executedConsumers(conn.toNodeId)
                .filterNot { reaches(execForward, conn.fromNodeId, it) }
            for (consumer in unreached) {
                out += ValidationIssue(
                    Severity.ERROR,
                    "'${nameOf(conn.fromNodeId)}' will not have run when '${nameOf(consumer)}' executes",
                    reason = IssueReason.NOT_EXEC_UPSTREAM,
                    args = listOf(nameOf(conn.fromNodeId), nameOf(consumer)),
                    nodes = setOf(conn.fromNodeId, consumer),
                    connectionId = conn.id,
                    // Only the consumer: the source itself is fine, it just runs too late.
                    blockedNodes = setOf(consumer),
                    blockedConnections = setOf(conn.id),
                )
            }
        }
    }

    /**
     * A data wire from one side of a fork to the other carries nothing, and must
     * say so rather than falling back in silence.
     *
     * [validateStrictDataSemantics] cannot catch this: it walks raw exec edges, in
     * which both of a fork's outputs are ordinary forward edges, so the source
     * *is* upstream of the consumer and the wire looks fine. What it cannot see is
     * that the deferred branch walks a **snapshot** taken when the fork ran — so a
     * node on the immediate branch may have run, and produced exactly what the
     * wire promises, and the consumer will still read nothing and quietly use its
     * form value instead. Which is the one thing this validator exists to prevent:
     * substituting a typed-in value for a wire drawn on the canvas.
     *
     * Both directions are wrong and both are reported. Immediate → deferred is the
     * snapshot; deferred → immediate is worse still, since the immediate branch
     * has finished by the time the deferred one starts.
     *
     * Nothing *before* the fork is affected: it ran before the snapshot was taken,
     * so it is visible to both branches. This costs a graph with no fork nothing
     * at all.
     */
    @Suppress("LoopWithTooManyJumpStatements") // Two independent "not this edge" guards; nesting them reads worse.
    private fun validateForkBranches(out: MutableList<ValidationIssue>) {
        val forks = workflow.nodes.filter { it.isFork() }
        if (forks.isEmpty()) return
        val execForward = mutableMapOf<NodeId, MutableList<NodeId>>()
        workflow.execConnections.forEach {
            execForward.getOrPut(it.fromNodeId) { mutableListOf() } += it.toNodeId
        }
        for (fork in forks) {
            val fromOut = execReachableFrom(execForward, fork.id, ExecPorts.OUT)
            val fromResumed = execReachableFrom(execForward, fork.id, ExecPorts.RESUMED)
            // Only what one branch reaches and the other does not. A node both
            // reach is a diamond: it runs once per incoming pulse, so on each walk
            // it sees whatever that walk produced, and a wire into it is fine.
            val immediate = fromOut - fromResumed
            val deferred = fromResumed - fromOut
            for (conn in workflow.dataConnections) {
                if (isPullNode(conn.fromNodeId)) continue
                val crosses = (conn.fromNodeId in immediate && conn.toNodeId in deferred) ||
                    (conn.fromNodeId in deferred && conn.toNodeId in immediate)
                if (!crosses) continue
                out += ValidationIssue(
                    Severity.ERROR,
                    "'${nameOf(conn.fromNodeId)}' and '${nameOf(conn.toNodeId)}' are on opposite " +
                        "branches of '${fork.name}', so nothing can be passed between them",
                    reason = IssueReason.FORK_BRANCH_CROSS,
                    args = listOf(nameOf(conn.fromNodeId), nameOf(conn.toNodeId), fork.name),
                    nodes = setOf(conn.fromNodeId, conn.toNodeId),
                    connectionId = conn.id,
                    // Only the consumer, as everywhere else: the source is fine.
                    blockedNodes = executedConsumers(conn.toNodeId),
                    blockedConnections = setOf(conn.id),
                )
            }
        }
    }

    /** Every node reached by following exec edges out of [port] on [from], excluding [from]. */
    private fun execReachableFrom(
        execForward: Map<NodeId, List<NodeId>>,
        from: NodeId,
        port: PortName,
    ): Set<NodeId> {
        val seen = mutableSetOf<NodeId>()
        val stack = ArrayDeque(workflow.outgoingExec(from, port).map { it.toNodeId })
        while (stack.isNotEmpty()) {
            val current = stack.removeLast()
            if (seen.add(current)) execForward[current]?.forEach { stack.addLast(it) }
        }
        return seen
    }

    /**
     * The nodes that actually execute and so eventually read whatever arrives at
     * [nodeId]: [nodeId] itself when it is pulsed, or — when it is a pull-side node —
     * everything its output reaches by following data edges through further pull-side
     * nodes. A chain of transforms therefore resolves to the actions and triggers at
     * its far end.
     *
     * Empty for a pull-side node wired to nothing, which is a warning on its own
     * ([validateValueNodesAreUsed]) and no reason to also fail the edge feeding it.
     */
    private fun executedConsumers(nodeId: NodeId): Set<NodeId> {
        if (!isPullNode(nodeId)) return setOf(nodeId)
        val consumers = mutableSetOf<NodeId>()
        val seen = mutableSetOf(nodeId)
        val stack = ArrayDeque(listOf(nodeId))
        while (stack.isNotEmpty()) {
            val current = stack.removeLast()
            for (conn in workflow.dataConnections.filter { it.fromNodeId == current }) {
                if (!isPullNode(conn.toNodeId)) {
                    consumers += conn.toNodeId
                    // A data cycle is reported by validateDataAcyclicity; `seen` only
                    // keeps this walk from spinning on one.
                } else if (seen.add(conn.toNodeId)) {
                    stack.addLast(conn.toNodeId)
                }
            }
        }
        return consumers
    }

    @Suppress("ReturnCount")
    private fun reaches(
        execForward: Map<NodeId, List<NodeId>>,
        start: NodeId,
        target: NodeId,
    ): Boolean {
        if (start == target) return true
        val seen = mutableSetOf<NodeId>()
        val stack = ArrayDeque(execForward[start] ?: emptyList())
        while (stack.isNotEmpty()) {
            val cur = stack.removeLast()
            if (cur == target) return true
            if (seen.add(cur)) execForward[cur]?.forEach { stack.addLast(it) }
        }
        return false
    }

    /** A node's display name, falling back to its id when it is gone. */
    private fun nameOf(id: NodeId): String = workflow.node(id)?.name ?: id.value

    /**
     * Both endpoints' ports, or null when either end names a node — or a node type —
     * that is not there. Distinguishing the two matters: without it a missing node
     * fell through to "unknown output port", which sends the user looking at a port
     * on a card that does not exist.
     */
    @Suppress("ReturnCount") // Two null-guards and the result; the alternative is nesting.
    private fun ExecConnection.endpoints(): Pair<Port?, Port?>? {
        val fromDef = workflow.node(fromNodeId)?.let { NodeTypeRegistry.byId(it.typeId) } ?: return null
        val toDef = workflow.node(toNodeId)?.let { NodeTypeRegistry.byId(it.typeId) } ?: return null
        val from = fromDef.port(fromPort)
            ?.takeIf { it.kind == PortKind.EXECUTION && it.direction == Direction.OUT }
        val to = toDef.port(toPort)
            ?.takeIf { it.kind == PortKind.EXECUTION && it.direction == Direction.IN }
        return from to to
    }

    /**
     * A cycle, and the edge whose removal breaks it.
     *
     * The closing edge has to come out of the search itself rather than be looked up
     * afterwards from the node pair: two exec edges between the same two nodes are
     * legal, and picking the wrong one would block a wire that was never in the loop.
     */
    private data class Cycle<E>(val nodes: List<NodeId>, val closing: E) {
        /** `A -> B -> A`, closing back on the node it started from. */
        fun path(name: (NodeId) -> String): String = (nodes + nodes.first()).joinToString(" -> ", transform = name)
    }

    /**
     * Every cycle in [edges], found by repeatedly searching with the previous
     * closing edges taken out.
     *
     * One search only ever reports one cycle, and a graph with two independent loops
     * would then have one of them quarantined and the other still live. Capped
     * because a pathological graph could otherwise cost a search per edge, and
     * because a user with eight simultaneous loops has been told enough.
     */
    private fun <E> findCycles(edges: List<E>, endpoints: (E) -> Pair<NodeId, NodeId>): List<Cycle<E>> {
        val found = mutableListOf<Cycle<E>>()
        val excluded = mutableSetOf<E>()
        while (found.size < MAX_REPORTED_CYCLES) {
            val cycle = findCycle(edges, endpoints, excluded) ?: break
            found += cycle
            excluded += cycle.closing
        }
        return found
    }

    /** The node order the cycle walk starts from; see the loop at the bottom. */
    private val startOrder: List<NodeId> get() = workflow.nodes.map { it.id }

    private fun <E> findCycle(
        edges: List<E>,
        endpoints: (E) -> Pair<NodeId, NodeId>,
        excluded: Set<E>,
    ): Cycle<E>? {
        val adj = mutableMapOf<NodeId, MutableList<Pair<NodeId, E>>>()
        edges.forEach { e ->
            val (from, to) = endpoints(e)
            adj.getOrPut(to) { mutableListOf() }
            if (e in excluded) return@forEach
            adj.getOrPut(from) { mutableListOf() } += to to e
        }
        val visited = mutableSetOf<NodeId>()
        val onStack = mutableSetOf<NodeId>()
        val path = mutableListOf<NodeId>()
        @Suppress("ReturnCount") // Textbook iterative-DFS cycle detection.
        fun dfs(node: NodeId): Cycle<E>? {
            visited += node
            onStack += node
            path += node
            for ((next, edge) in adj[node] ?: emptyList()) {
                if (next !in visited) {
                    dfs(next)?.let { return it }
                } else if (next in onStack) {
                    val cycleStart = path.indexOf(next)
                    return Cycle(path.subList(cycleStart, path.size).toList(), edge)
                }
            }
            onStack -= node
            path.removeAt(path.lastIndex)
            return null
        }
        // Started in the order the nodes were placed, not in whatever order the
        // adjacency map happened to build. Which edge of a loop is "the one going
        // back" depends entirely on where the walk began, and the answer has to be
        // both stable across runs and the one a user would point at: entering the
        // loop the way execution does makes the closing edge the wire that returns
        // to a node already running, which is the wire they drew last.
        for (start in startOrder + adj.keys) {
            if (start in adj && start !in visited) dfs(start)?.let { return it }
        }
        return null
    }

    private companion object {
        /** The kinds that are pulled on demand rather than pulsed. */
        val PULL_KINDS = setOf(NodeKind.VALUE, NodeKind.TRANSFORM)

        const val MAX_REPORTED_CYCLES = 8
    }
}
