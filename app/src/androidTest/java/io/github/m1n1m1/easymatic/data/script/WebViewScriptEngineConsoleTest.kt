package io.github.m1n1m1.easymatic.data.script

import androidx.javascriptengine.JavaScriptSandbox
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.m1n1m1.easymatic.core.service.ScriptConsoleLevel
import io.github.m1n1m1.easymatic.core.service.ScriptOutcome
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `console.log` coming back out of the sandbox.
 *
 * None of this is reachable from the JVM: the callback, its level constants and
 * its delivery timing are all the system WebView's, and `ScriptActionTest` only
 * covers what happens to messages once they have arrived. The precedent for
 * insisting on hardware is in this package — the bug that mattered last time (a
 * per-instance sandbox holder where the platform's limit is per *process*) was
 * invisible until these ran on a real phone.
 */
@RunWith(AndroidJUnit4::class)
class WebViewScriptEngineConsoleTest {

    private lateinit var engine: WebViewScriptEngine

    @Before
    fun setUp() {
        assumeTrue("No sandbox-capable WebView on this device", JavaScriptSandbox.isSupported())
        engine = WebViewScriptEngine(InstrumentationRegistry.getInstrumentation().targetContext)
    }

    @Test
    fun consoleOutputComesBackWithTheResult() = runBlocking {
        val outcome = engine.evaluate("""console.log("hello"); "done"""", TIMEOUT_MS)
        assumeConsoleSupported(outcome)

        assertEquals(ScriptOutcome.Value::class.java, outcome::class.java)
        assertTrue(outcome.console.toString(), outcome.console.any { it.message.contains("hello") })
    }

    @Test
    fun consoleErrorIsReportedAsAnError() = runBlocking {
        // The int-constant mapping is the one thing here that cannot be checked
        // against anything but the real ConsoleMessage.
        val outcome = engine.evaluate("""console.error("bad"); "done"""", TIMEOUT_MS)
        assumeConsoleSupported(outcome)

        val line = outcome.console.first { it.message.contains("bad") }
        assertEquals(ScriptConsoleLevel.ERROR, line.level)
    }

    @Test
    fun outputSurvivesAScriptThatNeverFinishes() = runBlocking {
        // The reason the drain lives in the `finally` rather than on the success
        // path. Without it, a script that logs and then loops reports a bare
        // timeout, and the line saying where it got to is thrown away.
        val outcome = engine.evaluate("""console.log("before the loop"); for(;;){}""", SHORT_TIMEOUT_MS)

        assertEquals(ScriptOutcome.Error::class.java, outcome::class.java)
        assumeConsoleSupported(outcome)
        assertTrue(
            outcome.console.toString(),
            outcome.console.any { it.message.contains("before the loop") },
        )
    }

    @Test
    fun aFloodIsCappedAndSaysSo() = runBlocking {
        // A runaway loop can emit tens of thousands of lines inside one timeout.
        // Uncapped they would cross the process boundary into a bounded run log
        // and evict whatever the user was trying to read.
        val outcome = engine.evaluate(
            """for (var i = 0; i < 5000; i++) { console.log("line " + i); } "done"""",
            TIMEOUT_MS,
        )
        assumeConsoleSupported(outcome)

        assertTrue("${outcome.console.size} lines", outcome.console.size <= CAP_WITH_NOTICE)
        assertTrue(outcome.console.toString(), outcome.console.last().message.contains("suppressed"))
    }

    @Test
    fun aScriptThatLogsNothingComesBackClean() = runBlocking {
        // Also the shape a device without JS_FEATURE_CONSOLE_MESSAGING reports:
        // an ordinary result and an empty console, never a failure.
        val outcome = engine.evaluate(""""quiet"""", TIMEOUT_MS)

        assertEquals(ScriptOutcome.Value("quiet"), outcome)
        assertTrue(outcome.console.isEmpty())
    }

    /**
     * Skips where the device's WebView predates console messaging. Feature
     * support tracks the WebView version rather than ours, so this is a
     * supported configuration and not a failure — scripts simply run without
     * their output being visible.
     */
    private fun assumeConsoleSupported(outcome: ScriptOutcome) {
        assumeTrue(
            "This WebView does not support console messaging",
            outcome.console.isNotEmpty(),
        )
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
        const val SHORT_TIMEOUT_MS = 300L

        /** The engine's own cap, plus the one line that reports the overflow. */
        const val CAP_WITH_NOTICE = 101
    }
}
