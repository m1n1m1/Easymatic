package com.example.ottomatic.engine.api

import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.ottomatic.ServiceLocator
import com.example.ottomatic.core.model.NodeId
import com.example.ottomatic.core.service.LogLevel
import com.example.ottomatic.core.service.LogSource
import com.example.ottomatic.data.WorkflowRepository
import com.example.ottomatic.engine.ExecutionContext
import com.example.ottomatic.engine.PULSE_ONLY
import com.example.ottomatic.engine.runFromTrigger
import com.example.ottomatic.engine.service.MacroEngineService
import com.example.ottomatic.engine.trigger.ApiTrigger
import com.example.ottomatic.engine.trigger.encodeToString
import com.example.ottomatic.engine.trigger.toTriggerEventWire
import com.example.ottomatic.engine.trigger.triggerOutputFrom
import com.example.ottomatic.core.model.PortName
import com.example.ottomatic.domain.model.schema.Item
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Starts a run that came from outside the app, once a front door has authorised it.
 *
 * Both doors end here, and neither takes any authorisation decision after this
 * point: by the time [start] is called the caller has been approved or has presented
 * a key, and the only checks left are about the *macro* rather than about who is
 * asking.
 */
object ApiRun {

    /**
     * Hands the run to the engine service, falling back to the process scope.
     *
     * `MacroEngineService.runManual`'s shape, fallback included, and for a sharper
     * version of its reason. Android 12+ forbids starting a foreground service from
     * the background, and unlike a widget tap **a third-party broadcast is on no
     * exemption list at all** — so on that door the throw is the expected case
     * rather than the OEM-specific one, and the fallback is the road. The provider
     * door fares better (Ottomatic's own process is already up to serve the call),
     * and an app exempted from battery optimisation fares better still, but nothing
     * here depends on either.
     *
     * What the fallback costs is the service's protection against the process being
     * reaped mid-run, which matters only for a macro that takes real time. That is
     * why `docs/EXTERNAL_API.md` points anything long at the provider — and why this
     * runs the macro either way rather than refusing.
     */
    @Suppress("TooGenericExceptionCaught") // Platform throws vary by OEM; any of them means "fall back".
    fun start(context: Context, target: ApiTriggerTarget, data: Map<PortName, Item>, caller: String) {
        val eventJson = data.toTriggerEventWire().encodeToString()
        val intent = Intent(context, MacroEngineService::class.java).apply {
            action = MacroEngineService.ACTION_RUN_API
            putExtra(MacroEngineService.EXTRA_WORKFLOW_ID, target.workflow.id)
            putExtra(MacroEngineService.EXTRA_NODE_ID, target.node.id.value)
            putExtra(MacroEngineService.EXTRA_TRIGGER_DATA, eventJson)
            putExtra(MacroEngineService.EXTRA_CALLER, caller)
        }
        try {
            context.startForegroundService(intent)
        } catch (e: Exception) {
            Log.w("Ottomatic", "Could not start the engine for an API run; running in-process", e)
            ServiceLocator.appScope.launch {
                runApiTrigger(
                    repository = ServiceLocator.workflowRepository,
                    context = ServiceLocator.executionContext,
                    workflowId = target.workflow.id,
                    nodeId = target.node.id.value,
                    eventJson = eventJson,
                    caller = caller,
                    deferredScope = ServiceLocator.appScope,
                )
            }
        }
    }

    /**
     * Runs one `trigger.api` node once.
     *
     * The workflow is re-loaded here rather than carried across in the intent,
     * because an `Intent` cannot hold one and because the reload is the honest
     * moment to check the macro is still there — the call may have crossed a delete.
     *
     * **The enabled check lives here rather than at the door**, which is worth
     * stating because the door does check it and this looks redundant. It is not:
     * the door's check is what produces the
     * [STATUS_DISABLED][com.example.ottomatic.domain.model.ApiContract.STATUS_DISABLED]
     * a caller can act on, and this one is what makes the guarantee true on the path
     * where nobody is listening for an answer — a broadcast, or a switch flipped
     * between the door and the service starting.
     *
     * Every lookup failure is silent, on `runManualTrigger`'s reasoning: the caller
     * has already been told an answer by the door, and there is nobody left here for
     * a second one to reach.
     */
    @Suppress("LongParameterList") // Every collaborator differs between the service and the fallback.
    suspend fun runApiTrigger(
        repository: WorkflowRepository,
        context: ExecutionContext,
        workflowId: String,
        nodeId: String,
        eventJson: String?,
        caller: String,
        deferredScope: CoroutineScope,
    ) {
        val workflow = repository.load(workflowId)?.takeIf { it.enabled } ?: return
        val node = workflow.node(NodeId(nodeId))?.takeIf { it.typeId == ApiTrigger.TYPE_ID } ?: return
        val output = eventJson?.let(::triggerOutputFrom) ?: PULSE_ONLY
        // The audit trail, and the only record anywhere that this run was not the
        // user's own doing. INFO rather than DEBUG: "why did this fire at 3 a.m.?"
        // is exactly the question the console exists to answer, and the answer is
        // this line.
        context.scoped(LogSource(workflow.id, LogSource.NO_RUN, node.id.value, node.name))
            .log("Started by ${caller.ifBlank { "another app" }}", LogLevel.INFO)
        runFromTrigger(context, workflow, node, output, deferredScope)
    }
}
