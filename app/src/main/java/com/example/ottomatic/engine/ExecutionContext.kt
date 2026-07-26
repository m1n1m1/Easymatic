package com.example.ottomatic.engine

import com.example.ottomatic.core.service.DeviceState
import com.example.ottomatic.core.service.MacroControl
import com.example.ottomatic.core.service.SystemServices
import com.example.ottomatic.core.service.UnknownDeviceState

/**
 * Context available to an [Action] or [ValueNode] while executing.
 *
 * Provides access to shared infrastructure without pulling Android types
 * into `engine/`: logging, system services and (optionally) macro control.
 *
 * [systemServices] changes the device; [deviceState] reads it. Actions use the
 * former, value nodes the latter — and a value node may use *only* the latter,
 * which is what its purity contract amounts to.
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
     * then reads null, which contributes no item and fails a gate closed.
     */
    val deviceState: DeviceState get() = UnknownDeviceState

    /** Optional handle to enable/disable other macros at runtime, or null. */
    val macroControl: MacroControl? get() = null

    fun log(message: String)
}
