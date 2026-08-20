package com.example.ottomatic.engine

import com.example.ottomatic.core.service.LogLevel
import com.example.ottomatic.core.service.LogSource
import com.example.ottomatic.core.model.PortName
import com.example.ottomatic.domain.model.Workflow
import com.example.ottomatic.domain.model.WorkflowNode
import com.example.ottomatic.domain.model.schema.Item
import com.example.ottomatic.engine.trigger.MacroEventBus
import com.example.ottomatic.engine.trigger.TriggerOutput
import kotlinx.coroutines.CoroutineScope
import kotlin.coroutines.cancellation.CancellationException

/**
 * A trigger firing with nothing on any data port — what `trigger.manual` emits.
 *
 * `trigger.manual` is a `pulseTriggerNode`, so it declares no data output at all
 * and there is nothing for the executor to cache. Named rather than written as an
 * empty map at each call site because `TriggerOutput` is a typealias over a
 * generic, and the bare `NodeOutput(emptyMap())` cannot infer its own arguments.
 */
val PULSE_ONLY: TriggerOutput = NodeOutput(emptyMap<PortName, Item>())

/**
 * One run of a workflow, starting at one trigger node.
 *
 * This is the whole of what happens when a trigger fires, minus the trigger: build
 * the executor against the workflow, walk the graph, and say "finished" afterwards
 * whatever the outcome. [WorkflowRunner] calls it for every event it collects, and
 * `MacroEngineService.ACTION_RUN_MANUAL` calls it for a tap on a widget or a
 * shortcut.
 *
 * Both callers go through here rather than each doing it themselves, and the
 * `finally` is why. Without it a throwing run never emits `"finished"`, so every
 * `trigger.macro_finished` chained onto the macro stops firing after the first bad
 * run — a failure that is invisible until someone wonders why their second macro
 * went quiet last Tuesday. That is not a rule worth writing down twice.
 *
 * A manual run needs no trigger *activation* at all: `trigger.manual`'s `activate`
 * registers nothing. So this path works whether or not the macro is armed, which
 * is what lets a tile run a macro whose background switch is off — and what lets
 * the button on the node's own card run one that is armed.
 *
 * Returns whether the run happened and completed without throwing — false covers
 * both a thrown run and a trigger node the validator quarantined.
 * [WorkflowRunner] ignores that; a widget tile draws it.
 *
 * [deferredScope] is where a `Wait Until` on this graph parks its second branch.
 * That branch deliberately outlives this function — which is what keeps
 * `"finished"` meaning "the macro got going", rather than waiting until morning
 * for a wait set the night before — so it needs a scope of its own, and the one
 * with the right lifetime is the arm's. Null runs it inline; see
 * [WorkflowExecutor].
 */
@Suppress("TooGenericExceptionCaught") // One bad run must not end the subscription, or crash a widget tap.
suspend fun runFromTrigger(
    context: ExecutionContext,
    workflow: Workflow,
    node: WorkflowNode,
    output: TriggerOutput = PULSE_ONLY,
    deferredScope: CoroutineScope? = null,
): Boolean {
    val executor = WorkflowExecutor(context.boundTo(workflow), deferredScope)
    return try {
        executor.executeFrom(workflow, node, output)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        context.scoped(LogSource(workflow.id, LogSource.NO_RUN, node.id.value, node.name))
            .log("Run from '${node.name}' failed: ${e.message}", LogLevel.ERROR)
        false
    } finally {
        // tryEmit under the hood, so this still lands on a cancelled coroutine.
        MacroEventBus.emit(MacroEventBus.macroEvent(workflow.id, "finished"))
    }
}
