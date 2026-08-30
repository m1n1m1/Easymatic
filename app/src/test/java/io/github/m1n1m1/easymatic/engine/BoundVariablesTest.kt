package io.github.m1n1m1.easymatic.engine

import io.github.m1n1m1.easymatic.core.service.LogSource
import io.github.m1n1m1.easymatic.core.service.VariableWrite
import io.github.m1n1m1.easymatic.core.service.Variables
import io.github.m1n1m1.easymatic.domain.model.VariableDeclaration
import io.github.m1n1m1.easymatic.domain.model.VariableRef
import io.github.m1n1m1.easymatic.domain.model.Workflow
import io.github.m1n1m1.easymatic.domain.registry.GlobalVariables
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The layer that turns a reference into a value: scope, declarations, constants.
 *
 * The store below it is a flat keyed map with no idea what a declaration is; every
 * rule about what a variable *means* lives here, and this is where it is stated.
 */
class BoundVariablesTest {

    private class FakeStore : Variables {
        val written = LinkedHashMap<String, String>()
        override fun get(ref: String): String? = written[ref]
        override fun set(ref: String, value: String): VariableWrite {
            written[ref] = value
            return VariableWrite.STORED
        }
    }

    private val counter = VariableDeclaration(id = "c1", name = "counter")
    private val seeded = VariableDeclaration(id = "s1", name = "retries", initialValue = "3")
    private val fixed = VariableDeclaration(id = "k1", name = "apiKey", initialValue = "secret", constant = true)
    private val shared = VariableDeclaration(id = "sh1", name = "quietHours")

    private val store = FakeStore()
    private val bound = BoundVariables(store, workflowId = "wf1", locals = listOf(counter, seeded, fixed))

    @After
    fun tearDown() = GlobalVariables.reset()

    @Test
    fun `the same local id in two workflows is two values`() {
        val other = BoundVariables(store, workflowId = "wf2", locals = listOf(counter))
        bound.set(VariableRef.localSpec(counter.id), "mine")
        other.set(VariableRef.localSpec(counter.id), "theirs")

        assertEquals("mine", bound.get(VariableRef.localSpec(counter.id)))
        assertEquals("theirs", other.get(VariableRef.localSpec(counter.id)))
    }

    @Test
    fun `a global is the same value from every workflow`() {
        GlobalVariables.hydrate(listOf(shared))
        val other = BoundVariables(store, workflowId = "wf2", locals = emptyList())
        bound.set(VariableRef.globalSpec(shared.id), "on")

        assertEquals("on", other.get(VariableRef.globalSpec(shared.id)))
    }

    @Test
    fun `an unset variable reads its declared initial value, without being stored`() {
        assertEquals("3", bound.get(VariableRef.localSpec(seeded.id)))
        // Seeding eagerly at arm time would fire `trigger.variable_change` for
        // every declared variable on every edit that re-arms.
        assertTrue(store.written.isEmpty())
    }

    @Test
    fun `an unset variable with no initial value reads as unavailable`() {
        // Null, not "", so a comparison over it fails closed rather than matching.
        assertNull(bound.get(VariableRef.localSpec(counter.id)))
    }

    @Test
    fun `a constant refuses the write and never reaches the store`() {
        assertEquals(VariableWrite.REFUSED_CONSTANT, bound.set(VariableRef.localSpec(fixed.id), "hacked"))
        assertTrue(store.written.isEmpty())
    }

    @Test
    fun `a constant answers from its declaration, so it cannot drift from it`() {
        assertEquals("secret", bound.get(VariableRef.localSpec(fixed.id)))
    }

    @Test
    fun `an undeclared reference refuses, and reads as unavailable`() {
        assertEquals(VariableWrite.REFUSED_UNDECLARED, bound.set(VariableRef.localSpec("gone"), "x"))
        assertNull(bound.get(VariableRef.localSpec("gone")))
        assertEquals(VariableWrite.REFUSED_UNDECLARED, bound.set("", "x"))
        assertNull(bound.get(""))
    }

    /**
     * The regression this whole design turns on.
     *
     * `DefaultExecutionContext`'s `Scoped` re-wraps the *original* delegate rather
     * than `this`, so a wrapper that let `by delegate` generate its `scoped` would
     * silently hand back a context pointing at the unscoped, process-wide store.
     * Because `WorkflowExecutor.pulse` scopes before running every single action,
     * that would leave workflow-local variables not working at all — at runtime
     * only, with macros quietly reading and writing each other's state.
     */
    @Test
    fun `scoping a bound context keeps the binding`() {
        val context = DefaultExecutionContext(systemServices = RecordingSystemServices(), variables = store)
            .boundTo(Workflow(id = "wf1", variables = listOf(counter)))

        val scoped = context.scoped(LogSource("wf1", 1L, "node", "Node"))

        assertSame(context.variables, scoped.variables)
        assertTrue(scoped.variables is BoundVariables)
        // And twice, since the executor recurses.
        assertTrue(scoped.scoped(LogSource("wf1", 2L)).variables is BoundVariables)
    }
}
