package com.example.ottomatic.data.script

import androidx.javascriptengine.JavaScriptSandbox
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.ottomatic.core.service.ScriptOutcome
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The real engine, which only exists on a device: the sandbox is provided by the
 * system WebView, so none of this can run on the JVM. `ScriptActionTest` covers
 * everything on the app's side of the boundary with the engine faked out; what
 * is left — and what only a device can answer — is whether V8 actually comes
 * back, and whether a bad script can hurt us.
 *
 * Every test is skipped rather than failed where the device has no usable
 * WebView, because that is a supported configuration: `action.script` reports
 * [ScriptOutcome.Unavailable] and macros carry on.
 */
@RunWith(AndroidJUnit4::class)
class WebViewScriptEngineTest {

    private lateinit var engine: WebViewScriptEngine

    @Before
    fun setUp() {
        assumeTrue("No sandbox-capable WebView on this device", JavaScriptSandbox.isSupported())
        engine = WebViewScriptEngine(InstrumentationRegistry.getInstrumentation().targetContext)
    }

    @Test
    fun evaluatesAndReturnsTheResult() = runBlocking {
        val outcome = engine.evaluate("JSON.stringify({ok: true, value: 1 + 1})", TIMEOUT_MS)
        assertEquals(ScriptOutcome.Value("""{"ok":true,"value":2}"""), outcome)
    }

    @Test
    fun aThrownErrorIsCaughtInsideTheScript() = runBlocking {
        // The wrapper `ScriptAction` builds catches in JavaScript, so a TypeError
        // arrives as a readable sentence rather than as a platform exception.
        val source = """
            (function () {
              try { throw new TypeError("nope"); }
              catch (e) { return JSON.stringify({ok: false, error: String(e)}); }
            })()
        """.trimIndent()
        val outcome = engine.evaluate(source, TIMEOUT_MS)
        assertTrue(outcome.toString(), (outcome as ScriptOutcome.Value).json.contains("nope"))
    }

    @Test
    fun aSyntaxErrorIsReportedRatherThanThrown() = runBlocking {
        val outcome = engine.evaluate("this is not javascript", TIMEOUT_MS)
        assertTrue(outcome.toString(), outcome is ScriptOutcome.Error)
    }

    @Test
    fun aNonStringResultComesBackEmptyRatherThanFailing() = runBlocking {
        // The reason `ScriptAction` stringifies: the platform silently yields
        // empty text for anything that is not a JavaScript String.
        val outcome = engine.evaluate("42", TIMEOUT_MS)
        assertEquals(ScriptOutcome.Value(""), outcome)
    }

    @Test
    fun aRunawayLoopIsStoppedByTheTimeout() = runBlocking {
        // The whole safety argument for shipping this: an infinite loop spins a
        // process we do not own, and closing the isolate ends it.
        val outcome = engine.evaluate("while (true) {}", SHORT_TIMEOUT_MS)
        assertTrue(outcome.toString(), outcome is ScriptOutcome.Error)
    }

    @Test
    fun theEngineStillWorksAfterARunawayScript() = runBlocking {
        engine.evaluate("while (true) {}", SHORT_TIMEOUT_MS)
        // The sandbox is shared for the life of the process, so one bad macro
        // must not take scripting away from every other one.
        assertEquals(
            ScriptOutcome.Value("ok"),
            engine.evaluate("\"ok\"", TIMEOUT_MS),
        )
    }

    @Test
    fun theScriptCannotReachTheNetwork() = runBlocking {
        // Not a hardening measure so much as the contract: everything a macro
        // does to the outside world stays in an action on the canvas.
        val outcome = engine.evaluate("String(typeof fetch) + \",\" + String(typeof XMLHttpRequest)", TIMEOUT_MS)
        assertEquals(ScriptOutcome.Value("undefined,undefined"), outcome)
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
        const val SHORT_TIMEOUT_MS = 500L
    }
}
