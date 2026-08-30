package io.github.m1n1m1.easymatic.engine

import io.github.m1n1m1.easymatic.core.service.LogSource
import io.github.m1n1m1.easymatic.core.service.VariableWrite
import io.github.m1n1m1.easymatic.core.service.Variables
import io.github.m1n1m1.easymatic.domain.model.VariableDeclaration
import io.github.m1n1m1.easymatic.domain.model.VariableRef
import io.github.m1n1m1.easymatic.domain.model.Workflow
import io.github.m1n1m1.easymatic.domain.registry.GlobalVariables

/**
 * A [Variables] bound to one workflow: it turns a ref spec into a store key, reads
 * a declaration's type and initial value, and refuses to write a constant.
 *
 * The store below it is a flat keyed map that knows nothing about declarations,
 * which is what keeps it small; everything that makes a variable a *declared* thing
 * lives here, in one class, on the boundary the executor already crosses once per
 * run.
 *
 * Two behaviours are worth stating outright:
 *
 * **A constant is never stored.** [get] answers from its declaration, so a constant
 * cannot drift out of agreement with the value the editor shows, and retyping one
 * needs no re-seeding of anything.
 *
 * **Initial values seed lazily, on read.** Writing every declared variable into the
 * store when a macro arms would fire `trigger.variable_change` for each one on every
 * edit that re-arms — precisely the "when this changes must not mean whenever anyone
 * looked" rule the store's own `set` exists to keep. Reading through to the
 * declaration costs one map lookup and fires nothing.
 */
class BoundVariables(
    private val store: Variables,
    private val workflowId: String,
    private val locals: List<VariableDeclaration>,
) : Variables {

    /**
     * The declaration [spec] names, or null when nothing is chosen or it was
     * deleted.
     *
     * Public because refs are ids: every log line and every trigger payload has to
     * come back through here to say the variable's *name*, or the console would be
     * a wall of UUIDs.
     */
    fun declarationOf(spec: String): VariableDeclaration? = when (val ref = VariableRef.parse(spec)) {
        null -> null
        is VariableRef.Local -> locals.firstOrNull { it.id == ref.id }
        is VariableRef.Global -> GlobalVariables.byId(ref.id)
    }

    @Suppress("ReturnCount") // Undeclared, constant, ordinary — three answers, three exits.
    override fun get(ref: String): String? {
        val declaration = declarationOf(ref) ?: return null
        if (declaration.constant) return declaration.initialValue
        return store.get(storeKey(ref)) ?: declaration.initialValue.takeIf { it.isNotEmpty() }
    }

    @Suppress("ReturnCount") // The mirror of [get]; the three cases are the contract.
    override fun set(ref: String, value: String): VariableWrite {
        val declaration = declarationOf(ref) ?: return VariableWrite.REFUSED_UNDECLARED
        if (declaration.constant) return VariableWrite.REFUSED_CONSTANT
        return store.set(storeKey(ref), value)
    }

    /** Safe once [declarationOf] has answered: a declaration implies a parseable ref. */
    private fun storeKey(ref: String) =
        VariableRef.storeKey(requireNotNull(VariableRef.parse(ref)), workflowId)
}

/**
 * This context with its variables bound to [workflow]'s declarations.
 *
 * Applied once per arm by [WorkflowRunner], which is the only place holding a
 * [Workflow] *and* building the executor. Everything downstream — every action,
 * every value node, every transform — sees the bound facade through the plain
 * `context.variables` it already reads.
 */
fun ExecutionContext.boundTo(workflow: Workflow): ExecutionContext = WorkflowBoundContext(
    delegate = this,
    bound = BoundVariables(variables, workflow.id, workflow.variables),
)

/**
 * The context wrapper. `by delegate` forwards everything but [variables].
 *
 * **[scoped] must be overridden**, and this is the whole design balanced on one
 * line. `DefaultExecutionContext`'s own `Scoped` re-wraps the *original* delegate
 * rather than `this`, so the generated forwarder would hand back a context whose
 * `variables` points at the unbound, process-wide store. Since
 * [WorkflowExecutor.pulse] scopes before running every single action, the binding
 * would be discarded for every node in the graph — silently, at runtime only, with
 * workflow-local variables quietly reading and writing each other across macros.
 *
 * This is the same hazard `ExecutionContext.scoped`'s KDoc describes, arriving from
 * the other direction: there, a second scoping call would have lost the first; here,
 * a scoping call would lose a *different* wrapper entirely. Re-wrapping keeps both
 * halves right — attribution is replaced, the binding survives.
 */
private class WorkflowBoundContext(
    private val delegate: ExecutionContext,
    private val bound: Variables,
) : ExecutionContext by delegate {

    override val variables: Variables get() = bound

    override fun scoped(source: LogSource): ExecutionContext =
        WorkflowBoundContext(delegate.scoped(source), bound)
}
