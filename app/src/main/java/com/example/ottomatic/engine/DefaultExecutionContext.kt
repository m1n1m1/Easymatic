package com.example.ottomatic.engine

import com.example.ottomatic.core.service.Contacts
import com.example.ottomatic.core.service.DeviceState
import com.example.ottomatic.core.service.LogEntry
import com.example.ottomatic.core.service.NoContacts
import com.example.ottomatic.core.service.LogLevel
import com.example.ottomatic.core.service.LogSource
import com.example.ottomatic.core.service.MacroControl
import com.example.ottomatic.core.service.NoPrompts
import com.example.ottomatic.core.service.NoScripts
import com.example.ottomatic.core.service.NoVariables
import com.example.ottomatic.core.service.Prompts
import com.example.ottomatic.core.service.ScriptEngine
import com.example.ottomatic.core.service.Variables
import com.example.ottomatic.core.service.SystemServices
import com.example.ottomatic.core.service.UnknownDeviceState
import com.example.ottomatic.engine.trigger.NoSensors
import com.example.ottomatic.engine.trigger.SensorReader

/**
 * Default [ExecutionContext] wiring core-provided services to the engine.
 * Pure Kotlin — depends only on `core/` types and the engine's own sensor port.
 *
 * The [logger] is injected so the app can route entries to the run log and to
 * Logcat without `engine/` importing Android. It takes a whole [LogEntry] rather
 * than a string because attribution — which workflow, which run, which node —
 * is decided by the executor and would not survive being flattened into the
 * message. It stays the **last** parameter so `DefaultExecutionContext(services) {}`
 * keeps meaning "swallow the logs", which is how most tests build one.
 */
@Suppress("LongParameterList") // One parameter per facade the context exposes; the interface sets the count.
class DefaultExecutionContext(
    override val systemServices: SystemServices,
    override val macroControl: MacroControl? = null,
    override val deviceState: DeviceState = UnknownDeviceState,
    override val sensors: SensorReader = NoSensors,
    override val scripts: ScriptEngine = NoScripts,
    override val variables: Variables = NoVariables,
    override val contacts: Contacts = NoContacts,
    override val prompts: Prompts = NoPrompts,
    private val logger: (LogEntry) -> Unit = {},
) : ExecutionContext {

    override fun log(message: String, level: LogLevel) = logger(LogEntry(level, message))

    override fun scoped(source: LogSource): ExecutionContext = Scoped(this, source, logger)
}

/**
 * A context that logs as [source] and is otherwise the one it wraps.
 *
 * `by delegate` is what keeps [systemServices], [ExecutionContext.scripts] and
 * every other facade pointing at the real ones — only logging is overridden.
 *
 * [scoped] is overridden even though the generated forwarder would be correct
 * here (a [LogSource] carries the whole attribution, so re-scoping through the
 * delegate produces the same object). Stating it makes the composition explicit
 * rather than a property of two things happening to line up.
 */
private class Scoped(
    private val delegate: ExecutionContext,
    private val source: LogSource,
    private val logger: (LogEntry) -> Unit,
) : ExecutionContext by delegate {

    override fun log(message: String, level: LogLevel) = logger(LogEntry(level, message, source))

    override fun scoped(source: LogSource): ExecutionContext = Scoped(delegate, source, logger)
}
