package com.example.ottomatic.engine

import com.example.ottomatic.core.model.ConfigKey
import com.example.ottomatic.core.model.NodeId
import com.example.ottomatic.core.model.NodeTypeId
import com.example.ottomatic.core.service.Variables
import com.example.ottomatic.domain.model.WorkflowNode
import com.example.ottomatic.engine.action.SetVariableAction
import com.example.ottomatic.engine.value.VariableValue
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two halves of the graph's only writable state: `action.set_variable`
 * writes, `value.variable` reads.
 *
 * The store's own persistence is an Android file concern and lives in
 * `VariableStoreTest`; what matters here is the contract the nodes keep with it.
 */
class VariablesTest {

    /** An in-memory [Variables] recording what was written. */
    private class FakeVariables : Variables {
        val written = LinkedHashMap<String, String>()
        override fun get(name: String): String? = written[name]
        override fun set(name: String, value: String) {
            written[name] = value
        }
    }

    private val variables = FakeVariables()
    private val logs = mutableListOf<String>()
    private val context = DefaultExecutionContext(
        systemServices = RecordingSystemServices(),
        variables = variables,
        logger = { logs += it.message },
    )

    @Test
    fun `a written variable reads back`() = runBlocking {
        setVariable(name = "counter", value = "3")
        assertEquals("3", readVariable("counter"))
    }

    @Test
    fun `a variable nobody has written reads as unset, not as empty text`() = runBlocking {
        // Null means "unavailable", which fails a comparison closed. Empty text
        // would instead *match* a comparison against "".
        assertNull(readVariable("never-written"))
    }

    @Test
    fun `a blank name stores nothing and says why`() = runBlocking {
        setVariable(name = "   ", value = "3")
        assertTrue(variables.written.isEmpty())
        assertTrue(logs.toString(), logs.any { it.contains("no name") })
    }

    @Test
    fun `names are trimmed on both sides so they cannot drift apart`() = runBlocking {
        setVariable(name = "  counter  ", value = "9")
        assertEquals("9", readVariable("  counter "))
        assertEquals(setOf("counter"), variables.written.keys)
    }

    @Test
    fun `a blank name reads as unset rather than as the empty-named variable`() = runBlocking {
        variables.written[""] = "leaked"
        assertNull(readVariable(""))
    }

    private suspend fun setVariable(name: String, value: String) {
        val node = WorkflowNode(
            NodeId("v"), NodeTypeId("action.set_variable"), "Set", 0f, 0f,
            config = mapOf(ConfigKey("name") to name, ConfigKey("value") to value),
        )
        SetVariableAction().run(node, emptyMap(), context)
    }

    private suspend fun readVariable(name: String): Any? =
        VariableValue().readRaw(mapOf(ConfigKey("name") to name), context)?.value
}
