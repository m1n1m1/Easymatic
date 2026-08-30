package io.github.m1n1m1.easymatic.data.script

import androidx.javascriptengine.JavaScriptSandbox
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.m1n1m1.easymatic.core.service.ScriptOutcome
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What a user's script can actually rely on — the runtime half of what
 * `action.script`'s documentation promises.
 *
 * The isolate is V8 *without* the web platform: the ECMAScript standard library
 * and nothing else. That is a contract worth pinning, because it is invisible
 * from the app's side and set by whichever WebView the device happens to have.
 * If a future WebView starts shipping timers or `fetch` into the sandbox, the
 * documentation is wrong and this fails.
 */
@RunWith(AndroidJUnit4::class)
class ScriptEnvironmentTest {

    private lateinit var engine: WebViewScriptEngine

    @Before
    fun setUp() {
        assumeTrue("No sandbox-capable WebView on this device", JavaScriptSandbox.isSupported())
        engine = WebViewScriptEngine(InstrumentationRegistry.getInstrumentation().targetContext)
    }

    @Test
    fun theEcmascriptStandardLibraryIsAvailable() = runBlocking {
        assertPresent("Math", "JSON", "Date", "RegExp", "Promise", "Map", "Set", "WeakMap")
        assertPresent("Symbol", "Proxy", "Reflect", "BigInt", "Intl", "globalThis", "Error")
    }

    @Test
    fun noWebPlatformApiIsAvailable() = runBlocking {
        // The isolation that makes running user code safe: everything a macro
        // does to the outside world stays in an action on the canvas.
        assertAbsent("fetch", "XMLHttpRequest", "WebSocket", "navigator", "crypto")
        assertAbsent("window", "document", "self", "localStorage", "location")
        assertAbsent("TextEncoder", "TextDecoder", "URL", "URLSearchParams", "atob", "btoa")
        assertAbsent("performance", "structuredClone", "Worker")
    }

    @Test
    fun thereAreNoTimers() = runBlocking {
        // Load-bearing for what the docs can promise: `async` compiles, but there
        // is nothing to await on, so a script is effectively synchronous.
        assertAbsent("setTimeout", "setInterval", "queueMicrotask")
    }

    @Test
    fun thereIsNoModuleSystem() = runBlocking {
        // No imports, no npm — a script is one self-contained snippet.
        assertAbsent("require", "module", "process", "importScripts")
    }

    @Test
    fun modernSyntaxThroughEs2023Works() = runBlocking {
        // Chrome's V8, not a museum piece: everything a user is likely to reach
        // for compiles, up to and including the recent array methods.
        assertScript("3", "(() => { let [a] = [1]; const { b } = { b: 2 }; return String(a + b); })()")
        assertScript("3", "String(Math.max(...[1, 2, 3]))")
        assertScript("1,2", "[2, 1].toSorted().join()")
        assertScript("2", "String([1, 2, 3].findLast(v => v < 3))")
        assertScript("true", "String(Object.hasOwn({ a: 1 }, 'a'))")
        assertScript("3", "String([1, 2, 3].at(-1))")
        assertScript("bb", "'aa'.replaceAll('a', 'b')")
        assertScript("7", "'x7'.match(/(?<n>\\d)/).groups.n")
        assertScript("1", "String({ a: { b: 1 } }?.a?.b ?? 0)")
    }

    private suspend fun assertPresent(vararg names: String) {
        for (name in names) assertScript("true", "String(typeof $name !== 'undefined')", name)
    }

    private suspend fun assertAbsent(vararg names: String) {
        for (name in names) assertScript("false", "String(typeof $name !== 'undefined')", name)
    }

    private suspend fun assertScript(expected: String, source: String, label: String = source) {
        assertEquals(label, ScriptOutcome.Value(expected), engine.evaluate(source, TIMEOUT_MS))
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}
