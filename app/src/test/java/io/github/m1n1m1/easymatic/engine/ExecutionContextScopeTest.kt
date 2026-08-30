package io.github.m1n1m1.easymatic.engine

import io.github.m1n1m1.easymatic.core.service.LogEntry
import io.github.m1n1m1.easymatic.core.service.LogLevel
import io.github.m1n1m1.easymatic.core.service.LogSource
import io.github.m1n1m1.easymatic.core.service.NoVariables
import io.github.m1n1m1.easymatic.core.service.ScriptOutcome
import io.github.m1n1m1.easymatic.core.service.ScriptEngine
import io.github.m1n1m1.easymatic.engine.trigger.NoSensors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * How a log line learns which node it came from.
 *
 * An action never receives its own `WorkflowNode`, so it cannot attribute its
 * own logging; the executor hands it a context that already knows. Everything
 * here is about that handover being correct — a mistake in it is invisible until
 * a user opens a console and finds it empty.
 */
class ExecutionContextScopeTest {

    private val logs = mutableListOf<LogEntry>()

    private val engine = object : ScriptEngine {
        override suspend fun evaluate(source: String, timeoutMs: Long) = ScriptOutcome.Unavailable
    }

    private val root = DefaultExecutionContext(
        systemServices = RecordingSystemServices(),
        sensors = NoSensors,
        scripts = engine,
        variables = NoVariables,
        logger = { logs += it },
    )

    @Test
    fun `an unscoped context logs without attribution`() {
        root.log("nowhere in particular")
        assertNull(logs.single().source)
    }

    @Test
    fun `a scoped context stamps every line it takes`() {
        root.scoped(SOURCE_A).log("boom", LogLevel.ERROR)
        assertEquals(SOURCE_A, logs.single().source)
        assertEquals(LogLevel.ERROR, logs.single().level)
    }

    @Test
    fun `the default level is INFO, which is what an action saying something means`() {
        root.scoped(SOURCE_A).log("said something")
        assertEquals(LogLevel.INFO, logs.single().level)
    }

    @Test
    fun `re-scoping replaces the attribution entirely`() {
        // The regression test for the trap this design exists to avoid. Kotlin's
        // `by delegate` generates a forwarder for *every* member, so a two-step
        // API — scope the workflow, then scope the node — would have had the
        // second call forward to the *unscoped* context and silently drop the
        // first. Every line would then carry no workflow id, and a console
        // filtered by workflow would render empty with nothing to point at.
        root.scoped(SOURCE_A).scoped(SOURCE_B).log("second")
        assertEquals(SOURCE_B, logs.single().source)
        assertEquals(SOURCE_B.workflowId, logs.single().source?.workflowId)
    }

    @Test
    fun `scoping changes logging and nothing else`() {
        // `by delegate` is doing the work here. If someone ever rewrites `Scoped`
        // as a hand-built context, this is what catches an action being quietly
        // handed a NoScripts and every script on the device reporting
        // "unavailable" for reasons no one can find.
        val scoped = root.scoped(SOURCE_A)
        assertSame(root.systemServices, scoped.systemServices)
        assertSame(root.scripts, scoped.scripts)
        assertSame(root.sensors, scoped.sensors)
        assertSame(root.variables, scoped.variables)
        assertSame(root.deviceState, scoped.deviceState)
        assertSame(root.macroControl, scoped.macroControl)
    }

    private companion object {
        val SOURCE_A = LogSource("w1", runId = 1, nodeId = "n1", nodeName = "Notify")
        val SOURCE_B = LogSource("w2", runId = 2, nodeId = "n2", nodeName = "Run Script")
    }
}
