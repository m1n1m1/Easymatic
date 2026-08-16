package com.example.ottomatic.engine.ai

import com.example.ottomatic.core.model.ConfigKey
import com.example.ottomatic.core.model.NodeTypeId
import com.example.ottomatic.core.service.AiToolCall
import com.example.ottomatic.core.service.CallableMacro
import com.example.ottomatic.core.service.LogEntry
import com.example.ottomatic.core.service.LogLevel
import com.example.ottomatic.core.service.MacroControl
import com.example.ottomatic.core.service.MacroRunResult
import com.example.ottomatic.domain.model.ToolSpec
import com.example.ottomatic.domain.model.ToolTarget
import com.example.ottomatic.engine.DefaultExecutionContext
import com.example.ottomatic.engine.RecordingSystemServices
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Running one tool the model asked for.
 *
 * **Nothing here may throw**, and that is the property most worth pinning: a tool
 * that fails has to reach the model as a result it can act on, because the
 * alternative — an exception out of a tool call — ends a run the model could have
 * recovered.
 */
class NodeToolRunnerTest {

    private val logs = mutableListOf<LogEntry>()
    private val macros = RecordingMacroControl()
    private val context = DefaultExecutionContext(
        systemServices = RecordingSystemServices(),
        macroControl = macros,
        logger = { logs += it },
    )

    @After
    fun tearDown() = AiToolDepth.reset()

    private fun runnerFor(vararg specs: ToolSpec) =
        NodeToolRunner(NodeToolCatalog.build(specs.toList(), macros.callable), context)

    private fun node(typeId: String, vararg pinned: Pair<String, String>) = ToolSpec(
        target = ToolTarget.Node(NodeTypeId(typeId)),
        pinned = pinned.associate { (k, v) -> ConfigKey(k) to v },
    )

    private fun call(name: String, vararg arguments: Pair<String, String>) =
        AiToolCall(id = name, name = name, arguments = arguments.toMap())

    // ---- values and actions -----------------------------------------------------

    /** A value node needs no node at all — `readRaw` takes a config map and nothing else. */
    @Test
    fun `a value node answers with its reading`() = runBlocking {
        val result = runnerFor(node("value.now")).invoke(call("value_now"))
        assertFalse(result.isError)
        assertTrue(result.text.isNotBlank())
    }

    /** Most actions are effects, so "Done" is the honest answer rather than a placeholder. */
    @Test
    fun `an action with no data output answers Done`() = runBlocking {
        val result = runnerFor(node("action.notify")).invoke(call("action_notify", "text" to "hi"))
        assertEquals("Done", result.text)
        assertFalse(result.isError)
    }

    /**
     * The one rule the tool list exists to enforce. If the model could override a
     * pinned value by naming it again, pinning would buy nothing at all.
     */
    @Test
    fun `a pinned value wins over the model's argument`() = runBlocking {
        val services = RecordingSystemServices()
        val runner = NodeToolRunner(
            NodeToolCatalog.build(listOf(node("action.notify", "text" to "the pinned one"))),
            DefaultExecutionContext(
                systemServices = services,
                notifications = services.notifier,
                logger = { logs += it },
            ),
        )
        runner.invoke(call("action_notify", "text" to "the model's one"))
        assertEquals("the pinned one", services.notifier.titlesAndTexts.single().second)
    }

    @Test
    fun `an argument the author left open reaches the node`() = runBlocking {
        val services = RecordingSystemServices()
        val runner = NodeToolRunner(
            NodeToolCatalog.build(listOf(node("action.notify"))),
            DefaultExecutionContext(
                systemServices = services,
                notifications = services.notifier,
                logger = { logs += it },
            ),
        )
        runner.invoke(call("action_notify", "text" to "from the model"))
        assertEquals("from the model", services.notifier.titlesAndTexts.single().second)
    }

    // ---- the audit trail --------------------------------------------------------

    /**
     * The only record anywhere of what an unattended macro let a model do — which is
     * why it is INFO rather than DEBUG, and why it names the arguments.
     */
    @Test
    fun `every call is written to the run log with its arguments`() = runBlocking {
        runnerFor(node("action.notify")).invoke(call("action_notify", "text" to "hello there"))
        val line = logs.firstOrNull { it.level == LogLevel.INFO && it.message.contains("action_notify") }
        assertTrue("expected an INFO line naming the tool", line != null)
        assertTrue(line!!.message.contains("hello there"))
    }

    // ---- failing rather than throwing -------------------------------------------

    @Test
    fun `an unknown tool name is an error the model can act on`() = runBlocking {
        val result = runnerFor(node("value.now")).invoke(call("no_such_tool"))
        assertTrue(result.isError)
        assertTrue(result.text.contains("no_such_tool"))
    }

    // ---- macros -----------------------------------------------------------------

    @Test
    fun `a macro tool runs the macro and reports that it did`() = runBlocking {
        macros.callable = listOf(CallableMacro(id = "m", name = "Bed time", inputs = "room:TEXT"))
        val result = runnerFor(ToolSpec(ToolTarget.Macro("m"))).invoke(
            call("macro_Bed_time", "room" to "kitchen"),
        )
        assertFalse(result.isError)
        assertEquals("Done", result.text)
        assertEquals("m" to mapOf("room" to "kitchen"), macros.ran.single())
    }

    @Test
    fun `a macro that could not run reports why`() = runBlocking {
        macros.callable = listOf(CallableMacro(id = "m", name = "Bed time"))
        macros.result = MacroRunResult(ran = false, error = "\"Bed time\" is switched off")
        val result = runnerFor(ToolSpec(ToolTarget.Macro("m"))).invoke(call("macro_Bed_time"))
        assertTrue(result.isError)
        assertTrue(result.text.contains("switched off"))
    }

    /**
     * The guard depth alone cannot provide: a macro calling itself is a loop at depth
     * one, and would otherwise run twice before anything objected.
     */
    @Test
    fun `a macro already running as a tool is refused rather than re-entered`() = runBlocking {
        macros.callable = listOf(CallableMacro(id = "m", name = "Loop"))
        val runner = runnerFor(ToolSpec(ToolTarget.Macro("m")))
        var inner: String? = null
        macros.onRun = {
            // Standing in for the called macro's own agent node reaching back.
            inner = runner.invoke(call("macro_Loop")).text
        }
        runner.invoke(call("macro_Loop"))
        assertEquals("That macro is already running", inner)
    }

    private class RecordingMacroControl : MacroControl {
        var callable: List<CallableMacro> = emptyList()
        var result = MacroRunResult(ran = true)
        var onRun: (suspend () -> Unit)? = null
        val ran = mutableListOf<Pair<String, Map<String, String>>>()

        override fun enable(macroId: String) = true

        override fun disable(macroId: String) = true

        override suspend fun callable(): List<CallableMacro> = callable

        override suspend fun run(macroId: String, inputs: Map<String, String>): MacroRunResult {
            ran += macroId to inputs
            onRun?.invoke()
            return result
        }
    }
}
