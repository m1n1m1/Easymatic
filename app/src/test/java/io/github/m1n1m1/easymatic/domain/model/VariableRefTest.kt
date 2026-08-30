package io.github.m1n1m1.easymatic.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The persisted grammar of a variable reference.
 *
 * Two strings are at stake and they are deliberately different: the **spec** a
 * node's config holds, which must not name a workflow, and the **store key** a
 * value is filed under, which must.
 */
class VariableRefTest {

    @Test
    fun `a bare id is local and a g-prefixed one is global`() {
        assertEquals(VariableRef.Local("abc"), VariableRef.parse("abc"))
        assertEquals(VariableRef.Global("abc"), VariableRef.parse("g:abc"))
    }

    @Test
    fun `both spec forms round-trip`() {
        val local = VariableRef.Local("abc")
        val global = VariableRef.Global("abc")
        assertEquals(local, VariableRef.parse(VariableRef.spec(local)))
        assertEquals(global, VariableRef.parse(VariableRef.spec(global)))
    }

    @Test
    fun `a blank spec is nothing chosen, not a default`() {
        // Unlike ValueSource, which always has *some* source to fall back to, a
        // freshly placed node genuinely has no variable — and every node that reads
        // one branches on that to say so rather than writing somewhere unintended.
        assertNull(VariableRef.parse(""))
        assertNull(VariableRef.parse("   "))
    }

    @Test
    fun `the same local id in two workflows is two store keys`() {
        val ref = VariableRef.Local("abc")
        assertNotEquals(VariableRef.storeKey(ref, "wf1"), VariableRef.storeKey(ref, "wf2"))
    }

    @Test
    fun `a global key is the same wherever it is read from`() {
        val ref = VariableRef.Global("abc")
        assertEquals(VariableRef.storeKey(ref, "wf1"), VariableRef.storeKey(ref, "wf2"))
    }

    @Test
    fun `every local key of a workflow shares its scope prefix`() {
        // What `VariableStore.clearScope` sweeps by when a workflow is deleted.
        val prefix = VariableRef.scopePrefix("wf1")
        assertEquals(true, VariableRef.storeKey(VariableRef.Local("a"), "wf1").startsWith(prefix))
        assertEquals(false, VariableRef.storeKey(VariableRef.Global("a"), "wf1").startsWith(prefix))
        assertEquals(false, VariableRef.storeKey(VariableRef.Local("a"), "wf2").startsWith(prefix))
    }

    @Test
    fun `a spec never names the workflow it is in`() {
        // A config value that embedded its own file would have to be rewritten by
        // anything that ever copies a workflow.
        val spec = VariableRef.spec(VariableRef.Local("abc"))
        assertEquals(false, spec.contains(VariableRef.LOCAL_PREFIX))
    }
}
