// The single top-level type here is the *result* of the functions beside it, which
// are the subject of the file — naming it ApiTriggerTarget.kt would name the return
// value rather than the job.
@file:Suppress("MatchingDeclarationName")

package io.github.m1n1m1.easymatic.engine.api

import io.github.m1n1m1.easymatic.core.model.NodeId
import io.github.m1n1m1.easymatic.data.WorkflowRepository
import io.github.m1n1m1.easymatic.domain.model.ApiInputWire
import io.github.m1n1m1.easymatic.domain.model.ApiTriggerListWire
import io.github.m1n1m1.easymatic.domain.model.ApiTriggerWire
import io.github.m1n1m1.easymatic.domain.model.PortSpec
import io.github.m1n1m1.easymatic.domain.model.Workflow
import io.github.m1n1m1.easymatic.domain.model.WorkflowNode
import io.github.m1n1m1.easymatic.domain.registry.API_INPUTS_KEY
import io.github.m1n1m1.easymatic.domain.registry.API_LABEL_KEY
import io.github.m1n1m1.easymatic.domain.registry.API_TOKEN_KEY
import io.github.m1n1m1.easymatic.domain.registry.API_TRIGGER_TYPE_ID

/** One callable `trigger.api`, with everything a call needs already resolved. */
data class ApiTriggerTarget(
    val workflow: Workflow,
    val node: WorkflowNode,
    val specs: List<PortSpec>,
    val token: String,
    val label: String,
)

/**
 * Every `trigger.api` in [workflow], in the order they were placed.
 *
 * Pure, and that is deliberate: the whole of what
 * [ApiContract.METHOD_LIST][io.github.m1n1m1.easymatic.domain.model.ApiContract.METHOD_LIST]
 * answers is derived from here, so the payload's shape can be tested against a
 * hand-built `Workflow` with no repository, no files and no Android.
 */
fun apiTriggersIn(workflow: Workflow): List<ApiTriggerTarget> =
    workflow.nodes.filter { it.typeId == API_TRIGGER_TYPE_ID }.map { node ->
        ApiTriggerTarget(
            workflow = workflow,
            node = node,
            specs = PortSpec.parse(node.config[API_INPUTS_KEY]),
            token = node.config[API_TOKEN_KEY].orEmpty(),
            label = labelOf(workflow, node),
        )
    }

/**
 * What a calling app should call this trigger, falling back the way
 * `ManualTriggerRef` does: the trigger's own name, then the node's, then the
 * macro's.
 *
 * The node's name is skipped while it is still the palette default, because a
 * picker listing three entries all called "Called by Another App" is worse than one
 * listing the macro's name three times — at least the latter says which macro.
 */
private fun labelOf(workflow: Workflow, node: WorkflowNode): String =
    node.config[API_LABEL_KEY]?.takeIf { it.isNotBlank() }
        ?: node.name.takeIf { it.isNotBlank() && it != DEFAULT_NODE_NAME }
        ?: workflow.name

private const val DEFAULT_NODE_NAME = "Called by Another App"

/**
 * The trigger [macroId]/[nodeId] names, or null when there is no such thing.
 *
 * [nodeId] is **optional**, and a macro holding exactly one API trigger resolves
 * without it — which is what nearly every caller wants and what spares a shell
 * script a second UUID. A macro holding two is *ambiguous rather than arbitrary*:
 * it answers null, because picking the first would silently run one of two macros
 * the user deliberately separated, and that is a bug nobody would think to look for.
 */
suspend fun findApiTrigger(
    repository: WorkflowRepository,
    macroId: String,
    nodeId: String?,
): ApiTriggerTarget? {
    val workflow = repository.load(macroId) ?: return null
    val triggers = apiTriggersIn(workflow)
    return when {
        nodeId.isNullOrBlank() -> triggers.singleOrNull()
        else -> triggers.firstOrNull { it.node.id == NodeId(nodeId) }
    }
}

/**
 * Every callable trigger on the device.
 *
 * Loads each workflow in full rather than reading the summaries, because the ports
 * and the key live in a node's config and a summary carries neither. That is a file
 * read per macro, which is why this is on the `list` path — a rare, user-initiated
 * call from a picker — and never on the `run` path, which loads exactly one.
 */
suspend fun listApiTriggers(repository: WorkflowRepository): ApiTriggerListWire {
    val triggers = repository.list().flatMap { summary ->
        val workflow = repository.load(summary.id) ?: return@flatMap emptyList()
        apiTriggersIn(workflow).map { it.toWire() }
    }
    return ApiTriggerListWire(triggers = triggers)
}

private fun ApiTriggerTarget.toWire(): ApiTriggerWire = ApiTriggerWire(
    macroId = workflow.id,
    macroName = workflow.name,
    nodeId = node.id.value,
    label = label,
    enabled = workflow.enabled,
    token = token,
    inputs = specs.map { ApiInputWire(name = it.name, type = it.type?.name ?: PortSpec.ANY, list = it.list) },
)
