package io.github.m1n1m1.easymatic.engine

import io.github.m1n1m1.easymatic.core.permissions.Permission
import io.github.m1n1m1.easymatic.core.permissions.PermissionChecker
import io.github.m1n1m1.easymatic.core.permissions.PermissionRequirement
import io.github.m1n1m1.easymatic.core.permissions.PermissionStatus
import io.github.m1n1m1.easymatic.core.permissions.PrerequisiteType
import io.github.m1n1m1.easymatic.domain.model.WorkflowNode
import io.github.m1n1m1.easymatic.domain.registry.NodeTypeRegistry

/**
 * Computes the permission requirements that are not yet satisfied for a given
 * set of workflow nodes, so the editor / runner can gate macro activation on
 * them and the UI can drive the grant flow.
 *
 * The [checker] reports the current status of each [Permission]; callers
 * (typically an Activity) are responsible for launching the request flow for
 * the returned unsatisfied requirements.
 */
class PermissionController(
    private val checker: PermissionChecker,
) {

    /**
     * Returns the [PermissionRequirement]s that are not satisfied for the
     * trigger nodes in [nodes].
     *
     * Requirements that are not granted through the runtime dialog — accessibility
     * access, notification-listener access — are asked about through
     * [PermissionChecker.isPrerequisiteSatisfied], which has a system API behind
     * it. They used to be reported as unsatisfied unconditionally, which meant a
     * node could never be shown as ready however many settings pages the user had
     * visited.
     */
    fun unsatisfiedFor(nodes: List<WorkflowNode>): List<PermissionRequirement> {
        val requirements = nodes.mapNotNull { node ->
            NodeTypeRegistry.byId(node.typeId)?.permissionRequirements
        }.flatten()
        return requirements.filter { requirement ->
            val permission = requirement.manifestPermission
            when {
                requirement.type != PrerequisiteType.RUNTIME ->
                    !checker.isPrerequisiteSatisfied(requirement.type)
                permission == null -> true
                else -> checker.status(Permission(permission)) !is PermissionStatus.Granted
            }
        }.distinct()
    }

    /** True when every requirement for [nodes] is satisfied. */
    fun allSatisfied(nodes: List<WorkflowNode>): Boolean =
        unsatisfiedFor(nodes).isEmpty()
}
