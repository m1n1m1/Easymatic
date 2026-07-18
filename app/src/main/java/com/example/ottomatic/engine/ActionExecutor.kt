package com.example.ottomatic.engine

import com.example.ottomatic.domain.model.Workflow
import com.example.ottomatic.domain.model.WorkflowNode
import com.example.ottomatic.domain.registry.ActionRegistry
import kotlinx.coroutines.CoroutineScope

/**
 * Walks the workflow graph from a fired trigger and runs connected actions.
 *
 * Starting from the trigger node, it follows outgoing connections on port 0,
 * executes the target action, and recursively forwards each action's outputs
 * to the connected downstream nodes. The condition node's true/false branches
 * (ports 0 and 1) are honoured.
 */
class ActionExecutor(
    private val context: ExecutionContext,
) {

    suspend fun executeFrom(workflow: Workflow, triggerNode: WorkflowNode, triggerPayload: WorkflowPayload) {
        val initial = ActionInput(triggerNode, triggerPayload)
        executeNode(workflow, triggerNode, initial.payload, fromPortIndex = 0)
    }

    private suspend fun executeNode(
        workflow: Workflow,
        node: WorkflowNode,
        payload: WorkflowPayload,
        fromPortIndex: Int,
    ) {
        // Find connections leaving this node from the given output port.
        val outgoing = workflow.connections.filter {
            it.fromNodeId == node.id && it.fromPortIndex == fromPortIndex
        }
        outgoing.forEach { connection ->
            val targetNode = workflow.node(connection.toNodeId)
            val action = targetNode?.let { ActionRegistry.byId(it.typeId) }
            val input = targetNode?.let { ActionInput(it, payload) }
            val result = if (targetNode != null && action != null && input != null) {
                runCatching { action.execute(input, context) }
                    .getOrElse { e ->
                        context.log("Action ${targetNode.typeId} failed: ${e.message}")
                        null
                    }
            } else {
                null
            }
            // Forward each produced output port to its downstream nodes.
            if (targetNode != null && result != null) {
                result.outputs.forEach { (portIndex, outputPayload) ->
                    executeNode(workflow, targetNode, outputPayload, portIndex)
                }
            }
        }
    }
}
