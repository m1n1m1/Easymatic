package com.example.ottomatic.engine

import com.example.ottomatic.core.model.ConfigKey
import com.example.ottomatic.core.model.NodeId
import com.example.ottomatic.core.model.NodeTypeId
import com.example.ottomatic.core.service.VariableWrite
import com.example.ottomatic.core.service.Variables
import com.example.ottomatic.domain.model.VariableDeclaration
import com.example.ottomatic.domain.model.VariableRef
import com.example.ottomatic.domain.model.Workflow
import com.example.ottomatic.domain.model.WorkflowNode
import com.example.ottomatic.domain.model.config.ValueType
import com.example.ottomatic.domain.registry.GlobalVariables
import com.example.ottomatic.engine.action.SetVariableAction
import com.example.ottomatic.engine.value.VariableValue
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two halves of the graph's only writable state: `action.set_variable`
 * writes, `value.variable` reads — through the declarations a workflow is bound to.
 *
 * The store's own persistence is an Android file concern and lives in
 * `VariableStoreTest`; what matters here is the contract the nodes keep with it.
 */
class VariablesTest {

    /** An in-memory store, standing in for `VariableStore`: keyed, and never refuses. */
    private class FakeStore : Variables {
        val written = LinkedHashMap<String, String>()
        override fun get(ref: String): String? = written[ref]
        override fun set(ref: String, value: String): VariableWrite {
            written[ref] = value
            return VariableWrite.STORED
        }
    }

    private val counter = VariableDeclaration(id = "c1", name = "counter", type = ValueType.WHOLE_NUMBER)
    private val greeting = VariableDeclaration(id = "g1", name = "greeting")
    private val apiKey = VariableDeclaration(
        id = "k1",
        name = "apiKey",
        initialValue = "secret",
        constant = true,
    )

    private val store = FakeStore()
    private val logs = mutableListOf<String>()
    private val workflow = Workflow(id = "wf", variables = listOf(counter, greeting))
    private val context = DefaultExecutionContext(
        systemServices = RecordingSystemServices(),
        variables = store,
        logger = { logs += it.message },
    ).boundTo(workflow)

    @After
    fun tearDown() = GlobalVariables.reset()

    @Test
    fun `a written variable reads back`() = runBlocking {
        setVariable(ref = VariableRef.localSpec(greeting.id), value = "hello")
        assertEquals("hello", readVariable(VariableRef.localSpec(greeting.id)))
    }

    @Test
    fun `a variable nobody has written reads as unset, not as empty text`() = runBlocking {
        // Null means "unavailable", which fails a comparison closed. Empty text
        // would instead *match* a comparison against "".
        assertNull(readVariable(VariableRef.localSpec(greeting.id)))
    }

    @Test
    fun `an unwritten variable reads its declared initial value`() = runBlocking {
        val seeded = VariableDeclaration(id = "s1", name = "retries", initialValue = "3")
        val bound = DefaultExecutionContext(systemServices = RecordingSystemServices(), variables = store)
            .boundTo(Workflow(id = "wf", variables = listOf(seeded)))
        assertEquals("3", VariableValue().readRaw(refConfig(seeded.id), bound)?.value)
        // Seeded lazily on the read: writing it at arm time would fire
        // `trigger.variable_change` for every declared variable on every re-arm.
        assertTrue(store.written.isEmpty())
    }

    @Test
    fun `the declared type is what comes off the port, not text`() = runBlocking {
        setVariable(ref = VariableRef.localSpec(counter.id), value = "7")
        assertEquals(7, readVariable(VariableRef.localSpec(counter.id)))
    }

    @Test
    fun `a value that does not fit its declared type lands on that type's zero`() = runBlocking {
        setVariable(ref = VariableRef.localSpec(counter.id), value = "not a number")
        // `ValueType.convert` is total, so a declaration retyped after the fact
        // degrades rather than throwing mid-run.
        assertEquals(0, readVariable(VariableRef.localSpec(counter.id)))
    }

    @Test
    fun `no variable chosen stores nothing and says why`() = runBlocking {
        setVariable(ref = "", value = "3")
        assertTrue(store.written.isEmpty())
        assertTrue(logs.toString(), logs.any { it.contains("no variable chosen") })
    }

    @Test
    fun `a reference to a deleted declaration stores nothing`() = runBlocking {
        setVariable(ref = VariableRef.localSpec("gone"), value = "3")
        assertTrue(store.written.isEmpty())
    }

    @Test
    fun `a constant refuses the write, names itself and never reaches the store`() = runBlocking {
        GlobalVariables.hydrate(listOf(apiKey))
        setVariable(ref = VariableRef.globalSpec(apiKey.id), value = "hacked")
        assertTrue(store.written.isEmpty())
        assertTrue(logs.toString(), logs.any { it.contains("'apiKey' is a constant") })
    }

    @Test
    fun `a constant reads its declared value without being stored`() = runBlocking {
        GlobalVariables.hydrate(listOf(apiKey))
        assertEquals("secret", readVariable(VariableRef.globalSpec(apiKey.id)))
        assertTrue(store.written.isEmpty())
    }

    @Test
    fun `the log line names the variable, not the reference`() = runBlocking {
        setVariable(ref = VariableRef.localSpec(greeting.id), value = "hi")
        assertTrue(logs.toString(), logs.any { it.contains("greeting = hi") })
    }

    @Test
    fun `the same declaration name in two workflows is two values`() = runBlocking {
        val other = Workflow(id = "other", variables = listOf(greeting.copy(id = "g2")))
        val otherContext = DefaultExecutionContext(
            systemServices = RecordingSystemServices(),
            variables = store,
        ).boundTo(other)

        setVariable(ref = VariableRef.localSpec(greeting.id), value = "mine")
        SetVariableAction().run(setNode(VariableRef.localSpec("g2"), "theirs"), emptyMap(), otherContext)

        assertEquals("mine", readVariable(VariableRef.localSpec(greeting.id)))
        assertEquals("theirs", VariableValue().readRaw(refConfig("g2"), otherContext)?.value)
    }

    private fun setNode(ref: String, value: String) = WorkflowNode(
        NodeId("v"), NodeTypeId("action.set_variable"), "Set", 0f, 0f,
        config = mapOf(ConfigKey("name") to ref, ConfigKey("value") to value),
    )

    private suspend fun setVariable(ref: String, value: String) {
        SetVariableAction().run(setNode(ref, value), emptyMap(), context)
    }

    private fun refConfig(id: String) = mapOf(ConfigKey("name") to VariableRef.localSpec(id))

    private suspend fun readVariable(ref: String): Any? =
        VariableValue().readRaw(mapOf(ConfigKey("name") to ref), context)?.value
}
