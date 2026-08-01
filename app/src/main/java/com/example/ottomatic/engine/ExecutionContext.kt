package com.example.ottomatic.engine

import com.example.ottomatic.core.service.DeviceState
import com.example.ottomatic.core.service.MacroControl
import com.example.ottomatic.core.service.NoScripts
import com.example.ottomatic.core.service.NoVariables
import com.example.ottomatic.core.service.ScriptEngine
import com.example.ottomatic.core.service.Variables
import com.example.ottomatic.core.service.SystemServices
import com.example.ottomatic.core.service.UnknownDeviceState
import com.example.ottomatic.engine.trigger.NoSensors
import com.example.ottomatic.engine.trigger.SensorReader

/**
 * Context available to an [Action] or [ValueNode] while executing.
 *
 * Provides access to shared infrastructure without pulling Android types
 * into `engine/`: logging, system services and (optionally) macro control.
 *
 * [systemServices] changes the device; [deviceState] and [sensors] read it.
 * Actions use the former, value nodes the latter two — and a value node may use
 * *only* the read-only pair, which is what its purity contract amounts to.
 *
 * [macroControl] is nullable: engine-only unit tests and environments without
 * a running [com.example.ottomatic.engine.service.MacroEngineService] may
 * leave it `null`; actions that require it should degrade gracefully.
 */
interface ExecutionContext {

    val systemServices: SystemServices

    /**
     * Read-only view of current device state, used by value nodes. Defaults to
     * [UnknownDeviceState] so engine-only tests need not supply one; a value node
     * then reads null, which contributes no item and fails a comparison closed.
     */
    val deviceState: DeviceState get() = UnknownDeviceState

    /**
     * One-shot sensor reads, used by the value nodes that pair with the sensor
     * triggers. Defaults to [NoSensors] for the same reason [deviceState]
     * defaults to unknown: an engine-only test reads null rather than inventing
     * a reading no sensor ever produced.
     */
    val sensors: SensorReader get() = NoSensors

    /**
     * Runs the JavaScript behind `action.script`. Defaults to [NoScripts] so
     * engine-only tests need not stand up a WebView sandbox: they then see the
     * same [com.example.ottomatic.core.service.ScriptOutcome.Unavailable] a
     * device without a usable WebView reports, which the action already has to
     * handle.
     *
     * An action, never a value node — an evaluation is slow and failable, so it
     * is not something the pull side may do.
     */
    val scripts: ScriptEngine get() = NoScripts

    /**
     * Named values that outlive a single run — written by `action.set_variable`,
     * read by `value.variable`. Defaults to [NoVariables] so an engine-only test
     * reads null and a comparison over it fails closed.
     *
     * The one facade both sides may touch: reading a variable is cheap and
     * cannot fail, so the pull side may do it, and writing one is a side effect,
     * so only an action does.
     */
    val variables: Variables get() = NoVariables

    /** Optional handle to enable/disable other macros at runtime, or null. */
    val macroControl: MacroControl? get() = null

    fun log(message: String)
}
