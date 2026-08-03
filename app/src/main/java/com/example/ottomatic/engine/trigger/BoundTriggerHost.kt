package com.example.ottomatic.engine.trigger

import com.example.ottomatic.core.trigger.TriggerEvent
import com.example.ottomatic.domain.model.VariableDeclaration
import com.example.ottomatic.domain.model.VariableRef
import com.example.ottomatic.domain.registry.GlobalVariables
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map

/**
 * A [TriggerHost] that knows which workflow is arming against it, so a
 * variable-change trigger watches the right scope.
 *
 * The host itself is one process-global object handed to every runner, and
 * `Trigger.activate(config, node, host)` is never given a workflow — deliberately,
 * because a trigger is a description of an event source and not of the graph that
 * wants it. [WorkflowRunner.run] *does* hold the workflow, and is the only place
 * triggers are activated, so the scope is applied there by wrapping the host for
 * the duration of one arm.
 *
 * `by delegate` forwards every other platform capability untouched. There is no
 * re-entrancy trap of the kind
 * [com.example.ottomatic.engine.boundTo] has to guard against: nothing ever wraps a
 * host a second time.
 *
 * Both directions are handled in one place on purpose. A ref goes in and is turned
 * into a store key; the payload comes back and its key is turned into the
 * variable's **name**. One function applies the encoding and the same function
 * un-applies it, so a `w:<uuid>:<uuid>` store key can never leak out into a
 * notification or a log line.
 */
class BoundTriggerHost(
    private val delegate: TriggerHost,
    private val workflowId: String,
    private val locals: List<VariableDeclaration>,
) : TriggerHost by delegate {

    @Suppress("ReturnCount") // Two "nothing to watch" guards and the flow.
    override fun variableChanges(name: String): Flow<TriggerEvent> {
        val ref = VariableRef.parse(name) ?: return emptyFlow()
        val declaration = when (ref) {
            is VariableRef.Local -> locals.firstOrNull { it.id == ref.id }
            is VariableRef.Global -> GlobalVariables.byId(ref.id)
        } ?: return emptyFlow()
        val key = VariableRef.storeKey(ref, workflowId)
        return delegate.variableChanges(key).map { event ->
            event.copy(payload = event.payload + (VariableChangeTrigger.KEY_NAME to declaration.name))
        }
    }
}
