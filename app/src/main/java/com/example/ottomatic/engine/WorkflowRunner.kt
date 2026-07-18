package com.example.ottomatic.engine

import com.example.ottomatic.domain.model.NodeKind
import com.example.ottomatic.domain.model.Workflow
import com.example.ottomatic.domain.registry.NodeTypeRegistry
import com.example.ottomatic.domain.registry.TriggerRegistry
import com.example.ottomatic.engine.trigger.TriggerEvent
import com.example.ottomatic.engine.trigger.TriggerHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Drives a workflow: activates every trigger node and, on each trigger event,
 * dispatches the payload through the [ActionExecutor].
 *
 * Each trigger runs in its own coroutine; cancelling the returned [Job]
 * tears down all trigger flows and (for schedule triggers) their armed work.
 */
class WorkflowRunner(
    private val host: TriggerHost,
    private val context: ExecutionContext,
) {

    fun run(scope: CoroutineScope, workflow: Workflow): Job {
        val executor = ActionExecutor(context)
        val triggers = workflow.nodes.filter {
            NodeTypeRegistry.byId(it.typeId)?.kind == NodeKind.TRIGGER
        }
        // Activate all triggers synchronously so their flows are registered
        // before this method returns. Otherwise a caller that fires a manual
        // trigger immediately after run() would race with coroutine startup.
        val activeTriggers = triggers.mapNotNull { node ->
            val trigger = TriggerRegistry.byId(node.typeId) ?: return@mapNotNull null
            val flow = trigger.activate(node, host)
            ActiveTrigger(node, flow, trigger)
        }
        return scope.launch {
            for (active in activeTriggers) {
                launch {
                    active.flow.collect { event ->
                        onTriggerFired(executor, workflow, event)
                    }
                }
            }
        }
    }

    private data class ActiveTrigger(
        val node: com.example.ottomatic.domain.model.WorkflowNode,
        val flow: kotlinx.coroutines.flow.Flow<TriggerEvent>,
        val trigger: com.example.ottomatic.engine.trigger.Trigger,
    )

    private suspend fun onTriggerFired(
        executor: ActionExecutor,
        workflow: Workflow,
        event: TriggerEvent,
    ) {
        val triggerNode = workflow.node(event.triggerNodeId) ?: return
        val payload = WorkflowPayload(event.payload)
        executor.executeFrom(workflow, triggerNode, payload)
    }
}
