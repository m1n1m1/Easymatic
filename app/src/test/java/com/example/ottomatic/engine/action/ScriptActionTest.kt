package com.example.ottomatic.engine.action

import com.example.ottomatic.core.model.ConfigKey
import com.example.ottomatic.core.model.NodeId
import com.example.ottomatic.core.model.PortName
import com.example.ottomatic.core.service.ScriptEngine
import com.example.ottomatic.core.service.ScriptOutcome
import com.example.ottomatic.domain.model.Direction
import com.example.ottomatic.domain.model.PortKind
import com.example.ottomatic.domain.model.Workflow
import com.example.ottomatic.domain.model.WorkflowNode
import com.example.ottomatic.domain.model.items.HttpResponseItem
import com.example.ottomatic.domain.model.schema.Item
import com.example.ottomatic.domain.model.schema.ItemSchema
import com.example.ottomatic.domain.model.schema.asText
import com.example.ottomatic.domain.registry.NodeTypeRegistry
import com.example.ottomatic.domain.registry.SCRIPT_INPUTS_KEY
import com.example.ottomatic.domain.registry.SCRIPT_OUTPUTS_KEY
import com.example.ottomatic.domain.registry.effectivePorts
import com.example.ottomatic.domain.registry.SCRIPT_TYPE_ID
import com.example.ottomatic.engine.DefaultExecutionContext
import com.example.ottomatic.engine.RecordingSystemServices
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `action.script` with the engine faked out.
 *
 * The JavaScript itself needs a device WebView and is covered by
 * `WebViewScriptEngineTest` in the instrumentation source set. What is testable
 * here — and what actually breaks — is the two ends: the source the action
 * builds, and how a returned document is spread across the ports the user named.
 */
class ScriptActionTest {

    /** Captures the source it was handed and replies with a canned outcome. */
    private class FakeEngine(private val reply: (String) -> ScriptOutcome) : ScriptEngine {
        var lastSource: String? = null
        var lastTimeoutMs: Long? = null
        override suspend fun evaluate(source: String, timeoutMs: Long): ScriptOutcome {
            lastSource = source
            lastTimeoutMs = timeoutMs
            return reply(source)
        }
    }

    private val logs = mutableListOf<String>()

    // region Spreading a result across ports

    @Test
    fun `each declared output takes its key from the returned object`() {
        val out = run(
            outputs = "count:WHOLE_NUMBER\nlabel:TEXT",
            outcome = ok("""{"count":7,"label":"hot"}"""),
        )
        assertEquals(7, out[PortName("count")]?.value)
        assertEquals("hot", out[PortName("label")]?.value)
    }

    @Test
    fun `a value is converted to the type its port declares`() {
        // JSON says 7.9; the port says whole number. The same total conversion
        // `transform.json_read` uses, so the two nodes cannot disagree.
        val out = run(outputs = "count:WHOLE_NUMBER", outcome = ok("""{"count":7.9}"""))
        assertEquals(7, out[PortName("count")]?.value)
    }

    @Test
    fun `a sole output may be returned bare`() {
        // `return 42` is what anyone writes first, and refusing it teaches nothing.
        assertEquals(42, run(outputs = "answer:WHOLE_NUMBER", outcome = ok("42"))[PortName("answer")]?.value)
    }

    @Test
    fun `a sole output declared as an object still reads by key`() {
        val out = run(outputs = "answer:WHOLE_NUMBER", outcome = ok("""{"answer":42}"""))
        assertEquals(42, out[PortName("answer")]?.value)
    }

    @Test
    fun `a key the script never wrote lands on the fallback`() {
        val out = run(
            outputs = "count:WHOLE_NUMBER\nmissing:WHOLE_NUMBER",
            outcome = ok("""{"count":1}"""),
            fallback = "-1",
        )
        assertEquals(1, out[PortName("count")]?.value)
        assertEquals(-1, out[PortName("missing")]?.value)
    }

    @Test
    fun `an object output arrives as JSON text, ready to read again`() {
        val out = run(outputs = "body:TEXT", outcome = ok("""{"body":{"a":1}}"""))
        assertEquals("""{"a":1}""", out[PortName("body")]?.value)
    }

    // endregion

    // region Failure

    @Test
    fun `a thrown script fills every port with the fallback and says so`() {
        val out = run(
            outputs = "a:WHOLE_NUMBER\nb:WHOLE_NUMBER",
            outcome = ScriptOutcome.Value("""{"ok":false,"error":"TypeError: x is not a function"}"""),
            fallback = "0",
        )
        assertEquals(0, out[PortName("a")]?.value)
        assertEquals(0, out[PortName("b")]?.value)
        assertTrue(logs.toString(), logs.any { it.contains("TypeError") })
    }

    @Test
    fun `an engine failure is reported as the script's, not the device's`() {
        run(outputs = "a:TEXT", outcome = ScriptOutcome.Error("boom"))
        assertTrue(logs.toString(), logs.any { it.contains("failed") && it.contains("boom") })
    }

    @Test
    fun `an unavailable engine blames the device rather than the script`() {
        // A phone with no usable WebView cannot be fixed by editing the code, so
        // the message must not read like the user made a mistake.
        run(outputs = "a:TEXT", outcome = ScriptOutcome.Unavailable)
        assertTrue(logs.toString(), logs.any { it.contains("WebView") })
    }

    @Test
    fun `an unreadable reply from the engine is survived`() {
        val out = run(outputs = "a:TEXT", outcome = ScriptOutcome.Value("this is not json"), fallback = "safe")
        assertEquals("safe", out[PortName("a")]?.value)
    }

    @Test
    fun `an unconfigured node still produces its default port`() {
        val out = run(outputs = "", outcome = ok("""{"result":"hi"}"""))
        assertEquals("hi", out[PortName("result")]?.value)
    }

    // endregion

    // region The source handed to the engine

    @Test
    fun `inputs are inlined under their own names with their types intact`() {
        val engine = FakeEngine { ok("null") }
        execute(
            engine = engine,
            inputs = "battery:WHOLE_NUMBER\nnetwork:TEXT\nspare:ANY",
            outputs = "a:TEXT",
            data = mapOf(
                PortName("battery") to Item(41, ItemSchema.Primitive(Int::class)),
                PortName("network") to Item("wifi", ItemSchema.Primitive(String::class)),
            ),
        )
        val source = engine.lastSource.orEmpty()
        // A number must reach the script as a number, or `battery + 1` would concatenate.
        assertTrue(source, source.contains("var battery = 41;"))
        assertTrue(source, source.contains("""var network = "wifi";"""))
        // A declared but unwired port is null rather than absent, so referring to
        // it is not a ReferenceError.
        assertTrue(source, source.contains("var spare = null;"))
    }

    @Test
    fun `a struct arrives as a real object`() {
        // The reason "Anything" exists: no ValueType can say "object", and handing
        // a script a whole HTTP response is one of the most useful things it does.
        val engine = FakeEngine { ok("null") }
        val response = HttpResponseItem(statusCode = 200, body = "hi", headers = mapOf("k" to "v"))
        execute(
            engine = engine,
            inputs = "res:ANY",
            outputs = "a:TEXT",
            data = mapOf(PortName("res") to Item.of(response)),
        )
        val source = engine.lastSource.orEmpty()
        assertTrue(source, source.contains(""""statusCode":200"""))
        assertTrue(source, source.contains(""""body":"hi""""))
    }

    @Test
    fun `a script with no inputs binds nothing`() {
        val engine = FakeEngine { ok("null") }
        execute(engine = engine, inputs = "", outputs = "a:TEXT")
        // Only the wrapper's own `var __result` is left.
        val source = engine.lastSource.orEmpty()
        assertEquals(source, 1, Regex("var ").findAll(source).count())
    }

    @Test
    fun `a node straight from the palette binds exactly the ports it shows`() {
        // A blank config value decodes to the property's *default*, so a default
        // of "A:ANY" would have bound an A the card never showed — and would have
        // come back the moment a user deleted every input row.
        val engine = FakeEngine { ok("""{"result":"hello"}""") }
        val context = DefaultExecutionContext(
            systemServices = RecordingSystemServices(),
            scripts = engine,
            logger = { logs += it },
        )
        val fresh = WorkflowNode(NodeId("s"), SCRIPT_TYPE_ID, "Run Script", 0f, 0f)
        val out = runBlocking { ScriptAction().run(fresh, emptyMap(), context).dataOut }

        val definition = NodeTypeRegistry.byId(SCRIPT_TYPE_ID)!!
        val declared = effectivePorts(definition, Workflow(nodes = listOf(fresh)), fresh)
            .filter { it.kind == PortKind.DATA }
        assertEquals(emptyList<PortName>(), declared.filter { it.direction == Direction.IN }.map { it.name })
        assertEquals(listOf(PortName("result")), declared.filter { it.direction == Direction.OUT }.map { it.name })
        assertEquals(setOf(PortName("result")), out.keys)
        assertEquals(1, Regex("var ").findAll(engine.lastSource.orEmpty()).count())
    }

    @Test
    fun `an untyped output passes the raw value through`() {
        val out = run(outputs = "payload:ANY", outcome = ok("""{"payload":{"a":1}}"""))
        assertEquals("""{"a":1}""", out[PortName("payload")]?.asText())
    }

    @Test
    fun `an untyped output falls back like a typed one`() {
        val out = run(outputs = "payload:ANY", outcome = ScriptOutcome.Error("boom"), fallback = "none")
        assertEquals("none", out[PortName("payload")]?.asText())
    }

    @Test
    fun `the configured timeout reaches the engine`() {
        val engine = FakeEngine { ok("null") }
        execute(engine = engine, outputs = "a:TEXT", timeoutMs = "750")
        assertEquals(750L, engine.lastTimeoutMs)
    }

    @Test
    fun `a nonsense timeout is floored rather than passed through`() {
        // withTimeout(0) would fail every script before it started.
        val engine = FakeEngine { ok("null") }
        execute(engine = engine, outputs = "a:TEXT", timeoutMs = "0")
        assertEquals(1L, engine.lastTimeoutMs)
    }

    // endregion

    private fun ok(value: String) = ScriptOutcome.Value("""{"ok":true,"value":$value}""")

    private fun run(
        outputs: String,
        outcome: ScriptOutcome,
        fallback: String = "",
    ): Map<PortName, Item> = execute(FakeEngine { outcome }, outputs = outputs, fallback = fallback)

    @Suppress("LongParameterList") // One knob per config field the tests vary.
    private fun execute(
        engine: ScriptEngine,
        outputs: String,
        inputs: String = "",
        fallback: String = "",
        timeoutMs: String = "1000",
        data: Map<PortName, Item> = emptyMap(),
    ): Map<PortName, Item> = runBlocking {
        val context = DefaultExecutionContext(
            systemServices = RecordingSystemServices(),
            scripts = engine,
            logger = { logs += it },
        )
        val node = WorkflowNode(
            NodeId("s"), SCRIPT_TYPE_ID, "Run Script", 0f, 0f,
            config = mapOf(
                ConfigKey("script") to "return 1",
                SCRIPT_INPUTS_KEY to inputs,
                SCRIPT_OUTPUTS_KEY to outputs,
                ConfigKey("timeoutMs") to timeoutMs,
                ConfigKey("fallback") to fallback,
            ),
        )
        ScriptAction().run(node, data, context).dataOut
    }
}
