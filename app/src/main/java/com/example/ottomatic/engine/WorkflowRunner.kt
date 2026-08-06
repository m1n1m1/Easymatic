package com.example.ottomatic.engine

import com.example.ottomatic.core.model.NodeId
import com.example.ottomatic.core.service.LogLevel
import com.example.ottomatic.core.service.LogSource
import com.example.ottomatic.core.trigger.TriggerEvent
import com.example.ottomatic.core.trigger.TriggerSource
import com.example.ottomatic.domain.model.NodeKind
import com.example.ottomatic.domain.model.Workflow
import com.example.ottomatic.domain.model.WorkflowNode
import com.example.ottomatic.domain.registry.NodeTypeRegistry
import com.example.ottomatic.domain.registry.TriggerRegistry
import com.example.ottomatic.engine.trigger.BoundTriggerHost
import com.example.ottomatic.engine.trigger.MacroEventBus
import com.example.ottomatic.engine.trigger.TriggerHost
import com.example.ottomatic.engine.trigger.TriggerOutput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlin.coroutines.cancellation.CancellationException

/**
 * Drives a workflow: activates every trigger node and, on each trigger event,
 * dispatches the unified [NodeOutput] through the [WorkflowExecutor].
 *
 * Each trigger runs in its own coroutine; cancelling the returned [Job]
 * tears down all trigger flows and (for schedule triggers) their armed work.
 *
 * **One trigger's failure is that trigger's failure.** A workflow may hold several
 * independent triggers, and they are only in the same file because they act on the
 * same thing — so a malformed config on one, or a platform source that dies, must
 * not take the others with it. Three separate guards make that true, at three
 * different granularities:
 *
 *  - **arming** — a trigger that throws while being activated is dropped, and the
 *    rest still arm. `activateEncoded` decodes config eagerly, so one bad field is
 *    enough to reach here.
 *  - **the source** — a flow that throws is reported and its collector ends, while
 *    its siblings keep collecting. [supervisorScope] is what stops the sibling
 *    cancellation; the `catch` is what stops the crash, since an unhandled child
 *    exception would otherwise reach the thread's default handler either way.
 *  - **one event** — a run that throws is reported and the flow *keeps collecting*.
 *    Catching only at the outer level would silently unsubscribe a geofence for the
 *    rest of the arm because one run hit a bad value.
 *
 * A trigger fires unconditionally. The macro-level "only when I'm at home"
 * constraint is an `action.if` placed immediately after it, where the graph shows
 * the branch instead of hiding it in the trigger's settings.
 */
class WorkflowRunner(
    private val host: TriggerHost,
    private val context: ExecutionContext,
) {

    /**
     * Arms every trigger in [workflow] on [scope].
     *
     * [announceEnabled] controls the `"enabled"` [MacroEventBus] event. It is
     * true for a genuine enable (user toggle, boot, cold start), and false when
     * the engine is merely re-arming an already-enabled macro to pick up an
     * edit: [MacroEventBus] has `replay = 0`, so emitting there would re-fire
     * every `trigger.macro_enabled` node on every edit.
     */
    fun run(scope: CoroutineScope, workflow: Workflow, announceEnabled: Boolean = true): Job {
        // Both bindings happen exactly here, and this is the only place they can:
        // it is the one site that holds the workflow, builds the executor *and*
        // activates the triggers. Once per arm, so the declarations are snapshotted
        // with the graph — and editing one re-arms, because `runtimeSignature`
        // includes them.
        val boundHost = BoundTriggerHost(host, workflow.id, workflow.variables, context)
        val triggers = workflow.nodes.filter {
            NodeTypeRegistry.byId(it.typeId)?.kind == NodeKind.TRIGGER
        }
        // Signal that this macro has been enabled (its triggers are being armed).
        if (announceEnabled) {
            MacroEventBus.emit(MacroEventBus.macroEvent(workflow.id, "enabled"))
        }
        // Activate all triggers synchronously so their flows are registered
        // before this method returns. Otherwise a caller that fires a manual
        // trigger immediately after run() would race with coroutine startup.
        val activeTriggers = triggers.mapNotNull { node -> activate(workflow, node, boundHost) }
        return scope.launch {
            supervisorScope {
                for (active in activeTriggers) {
                    launch { collect(workflow, active) }
                }
            }
        }
    }

    /**
     * Registers one trigger's flow, or null if it could not be registered.
     *
     * `activateEncoded` decodes the node's config before it builds the flow, and a
     * platform source can refuse outright, so this is a real failure point rather
     * than a formality — and it used to abort the whole loop, leaving every trigger
     * after the bad one silently unarmed.
     */
    @Suppress("TooGenericExceptionCaught") // Whatever a trigger's activation throws, the others must still arm.
    private fun activate(workflow: Workflow, node: WorkflowNode, host: TriggerHost): ActiveTrigger? {
        val trigger = TriggerRegistry.byId(node.typeId) ?: return null
        return try {
            ActiveTrigger(node, trigger.activateEncoded(node, host))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logAt(workflow, node, "Could not arm '${node.name}': ${e.message}")
            null
        }
    }

    /**
     * Collects one trigger's events and runs the graph for each.
     *
     * The per-event failure is swallowed inside [runFromTrigger]: the flow is still
     * good, and the next event deserves its chance. That handling — and the
     * `"finished"` event that has to be emitted whether or not the run threw — is
     * shared with the widget/shortcut path rather than written here, because two
     * copies of it would eventually stop agreeing about what a failed run announces.
     */
    @Suppress("TooGenericExceptionCaught") // A dead trigger source must not take the workflow down.
    private suspend fun collect(workflow: Workflow, active: ActiveTrigger) {
        try {
            active.flow.collect { output ->
                runFromTrigger(context, workflow, active.node, output)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logAt(workflow, active.node, "'${active.node.name}' stopped listening: ${e.message}")
        }
    }

    /**
     * An arm-time or source-level line, attributed to its node but to no run —
     * these happen outside any run, which is exactly what [LogSource.NO_RUN] marks.
     */
    private fun logAt(workflow: Workflow, node: WorkflowNode, message: String) {
        context.scoped(LogSource(workflow.id, LogSource.NO_RUN, node.id.value, node.name))
            .log(message, LogLevel.ERROR)
    }

    private data class ActiveTrigger(val node: WorkflowNode, val flow: Flow<TriggerOutput>)
}
