package com.example.ottomatic.engine

import com.example.ottomatic.core.model.NodeId
import com.example.ottomatic.core.trigger.TriggerSource
import com.example.ottomatic.domain.model.NodeKind
import com.example.ottomatic.domain.model.Workflow
import com.example.ottomatic.domain.registry.NodeTypeRegistry
import com.example.ottomatic.domain.registry.TriggerRegistry
import com.example.ottomatic.engine.trigger.MacroEventBus
import com.example.ottomatic.engine.trigger.TriggerHost
import com.example.ottomatic.engine.trigger.TriggerOutput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Drives a workflow: activates every trigger node and, on each trigger event,
 * dispatches the unified [NodeOutput] through the [WorkflowExecutor].
 *
 * Each trigger runs in its own coroutine; cancelling the returned [Job]
 * tears down all trigger flows and (for schedule triggers) their armed work.
 *
 * A trigger fires unconditionally. The macro-level "only when I'm at home"
 * constraint is an `action.if` placed immediately after it, where the graph shows
 * the branch instead of hiding it in the trigger's settings.
 */
class WorkflowRunner(
    private val host: TriggerHost,
    private val context: ExecutionContext,
) {

    fun run(scope: CoroutineScope, workflow: Workflow): Job {
        val executor = WorkflowExecutor(context)
        val triggers = workflow.nodes.filter {
            NodeTypeRegistry.byId(it.typeId)?.kind == NodeKind.TRIGGER
        }
        // Signal that this macro has been enabled (its triggers are being armed).
        MacroEventBus.emit(
            com.example.ottomatic.core.trigger.TriggerEvent(
                source = TriggerSource.MACRO,
                triggerNodeId = NodeId.BROADCAST,
                payload = mapOf(
                    "event" to "enabled",
                    "macroId" to workflow.id,
                    "timestamp" to System.currentTimeMillis().toString(),
                ),
            ),
        )
        // Activate all triggers synchronously so their flows are registered
        // before this method returns. Otherwise a caller that fires a manual
        // trigger immediately after run() would race with coroutine startup.
        val activeTriggers = triggers.mapNotNull { node ->
            val trigger = TriggerRegistry.byId(node.typeId) ?: return@mapNotNull null
            val flow = trigger.activateEncoded(node, host)
            ActiveTrigger(node, flow)
        }
        return scope.launch {
            for (active in activeTriggers) {
                launch {
                    active.flow.collect { output ->
                        onTriggerFired(executor, workflow, active.node, output)
                    }
                }
            }
        }
    }

    private data class ActiveTrigger(
        val node: com.example.ottomatic.domain.model.WorkflowNode,
        val flow: kotlinx.coroutines.flow.Flow<TriggerOutput>,
    )

    private suspend fun onTriggerFired(
        executor: WorkflowExecutor,
        workflow: Workflow,
        triggerNode: com.example.ottomatic.domain.model.WorkflowNode,
        output: TriggerOutput,
    ) {
        executor.executeFrom(workflow, triggerNode, output)
        // Signal that this macro's execution has finished.
        MacroEventBus.emit(
            com.example.ottomatic.core.trigger.TriggerEvent(
                source = TriggerSource.MACRO,
                triggerNodeId = NodeId.BROADCAST,
                payload = mapOf(
                    "event" to "finished",
                    "macroId" to workflow.id,
                    "timestamp" to System.currentTimeMillis().toString(),
                ),
            ),
        )
    }
}
