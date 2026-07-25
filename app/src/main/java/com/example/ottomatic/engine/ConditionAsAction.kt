package com.example.ottomatic.engine

import com.example.ottomatic.domain.model.WorkflowNode
import com.example.ottomatic.core.model.PortName
import com.example.ottomatic.domain.model.schema.Item

/**
 * Runs a [ConditionNode] that has been *placed on the canvas* as if it were a
 * branching action, so the executor needs no knowledge of conditions at all.
 *
 * The placed form is the powerful one: the condition has its own exec input, its
 * own DATA inputs to wire, and `true`/`false` exec outputs that can each drive a
 * different downstream branch. The attached form (an
 * [com.example.ottomatic.domain.model.AttachedCondition] on some other node) goes
 * through [conditionsPass] instead — but both end up calling the same
 * [ConditionNode.evaluate], so the two placements can never disagree.
 */
internal class ConditionAsAction<C : Any>(
    private val condition: ConditionNode<C>,
) : ExecutableAction {

    override val definition: ActionNodeDefinition<C, Unit> = ActionNodeDefinition(
        typeId = condition.definition.typeId,
        displayName = condition.definition.displayName,
        description = condition.definition.description,
        category = condition.definition.category,
        icon = condition.definition.icon,
        schema = condition.definition.schema,
        output = null,
        execOutputs = ExecOutputs.BRANCH,
        wildcardInputs = condition.definition.wildcardInputs,
        hasDynamicPorts = condition.definition.hasDynamicPorts,
    )

    override suspend fun run(
        node: WorkflowNode,
        data: Map<PortName, Item>,
        context: ExecutionContext,
    ): EncodedNodeOutput {
        val matched = condition.evaluateRaw(node.config, node, data, context)
        return EncodedNodeOutput(
            execOut = listOf(if (matched) ExecutionRoute.TRUE.portName else ExecutionRoute.FALSE.portName),
            dataOut = emptyMap(),
            halt = false,
        )
    }
}
