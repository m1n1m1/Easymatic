package com.example.ottomatic.data.service

import android.content.Context
import com.example.ottomatic.core.service.CallableMacro
import com.example.ottomatic.core.service.MacroControl
import com.example.ottomatic.core.service.MacroRunResult
import com.example.ottomatic.data.WorkflowRepository
import com.example.ottomatic.domain.registry.API_INPUTS_KEY
import com.example.ottomatic.engine.ExecutionContext
import com.example.ottomatic.engine.api.ApiInputs
import com.example.ottomatic.engine.api.apiTriggersIn
import com.example.ottomatic.engine.api.findApiTrigger
import com.example.ottomatic.engine.runFromTrigger
import com.example.ottomatic.engine.service.MacroEngineService
import com.example.ottomatic.engine.NodeOutput
import kotlinx.coroutines.CoroutineScope

/**
 * Android-backed [MacroControl]. Dispatches the engine service's enable/disable
 * intents so the [WorkflowRepository] flag flip and trigger (dis)arming happen on the
 * engine service's supervisor scope.
 *
 * [callable] and [run] take the other road — straight through
 * [findApiTrigger] and [runFromTrigger], the same pair
 * [com.example.ottomatic.engine.api.ApiRun] uses — because their caller needs an
 * answer and an intent cannot give one.
 *
 * [executionContext] is a supplier rather than a value because the two are mutually
 * dependent: `ServiceLocator` builds the context *holding* this, so it does not exist
 * yet when this is constructed.
 */
class AndroidMacroControl(
    private val context: Context,
    private val repository: WorkflowRepository,
    private val executionContext: () -> ExecutionContext,
    private val deferredScope: CoroutineScope,
) : MacroControl {

    override fun enable(macroId: String): Boolean = dispatch(MacroEngineService.ACTION_ENABLE, macroId)

    override fun disable(macroId: String): Boolean = dispatch(MacroEngineService.ACTION_DISABLE, macroId)

    /**
     * Every enabled macro holding **exactly one** `trigger.api` node.
     *
     * Both filters match what [run] can actually do, which is the point of applying
     * them here rather than at the call: a macro that is switched off would fail every
     * time it was tried, and one holding two API triggers is *ambiguous rather than
     * arbitrary* — [findApiTrigger] refuses to guess between them, so offering it as a
     * tool would offer something that can never be run.
     *
     * A file read per macro, on [com.example.ottomatic.engine.api.listApiTriggers]'
     * reasoning: the ports live in a node's config and a summary carries none. This is
     * a once-per-node-execution call, never a per-turn one.
     */
    override suspend fun callable(): List<CallableMacro> =
        repository.list().mapNotNull { summary ->
            val workflow = repository.load(summary.id)?.takeIf { it.enabled } ?: return@mapNotNull null
            val target = apiTriggersIn(workflow).singleOrNull() ?: return@mapNotNull null
            CallableMacro(
                id = workflow.id,
                name = target.label,
                inputs = target.node.config[API_INPUTS_KEY].orEmpty(),
            )
        }

    /**
     * Runs the macro and waits.
     *
     * The enabled check is repeated here even though [callable] already applied it,
     * on [com.example.ottomatic.engine.api.ApiRun.runApiTrigger]'s reasoning: the list
     * was taken before the model chose, and a switch may have been flipped in between.
     *
     * [deferredScope] is the process scope rather than the calling run's, so an
     * `action.wait_until` inside the called macro defers as it would on any other
     * path instead of holding the tool call open for eight hours.
     */
    @Suppress("ReturnCount") // Three outcomes — not callable, switched off, ran — each with its own sentence.
    override suspend fun run(macroId: String, inputs: Map<String, String>): MacroRunResult {
        val target = findApiTrigger(repository, macroId, nodeId = null)
            ?: return MacroRunResult(ran = false, error = NOT_CALLABLE)
        if (!target.workflow.enabled) {
            return MacroRunResult(ran = false, error = "\"${target.label}\" is switched off")
        }
        val ran = runFromTrigger(
            context = executionContext(),
            workflow = target.workflow,
            node = target.node,
            output = NodeOutput(ApiInputs.read(inputs, target.specs)),
            deferredScope = deferredScope,
        )
        return if (ran) {
            MacroRunResult(ran = true)
        } else {
            MacroRunResult(ran = false, error = "\"${target.label}\" failed — see its own console")
        }
    }

    private fun dispatch(action: String, macroId: String): Boolean = runCatching {
        MacroEngineService.start(context, action, macroId)
        true
    }.getOrDefault(false)

    private companion object {
        const val NOT_CALLABLE =
            "That macro cannot be started by name — it needs exactly one \"Called by Another App\" trigger"
    }
}
