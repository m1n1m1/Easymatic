package com.example.ottomatic.engine

import com.example.ottomatic.core.service.DeviceState
import com.example.ottomatic.core.service.MacroControl
import com.example.ottomatic.core.service.SystemServices
import com.example.ottomatic.core.service.UnknownDeviceState

/**
 * Context available to an [Action] or [ConditionNode] while executing.
 *
 * Provides access to shared infrastructure without pulling Android types
 * into `engine/`: logging, system services and (optionally) macro control.
 *
 * [systemServices] changes the device; [deviceState] reads it. Actions use the
 * former, conditions the latter.
 *
 * [macroControl] is nullable: engine-only unit tests and environments without
 * a running [com.example.ottomatic.engine.service.MacroEngineService] may
 * leave it `null`; actions that require it should degrade gracefully.
 */
interface ExecutionContext {

    val systemServices: SystemServices

    /**
     * Read-only view of current device state, used by conditions. Defaults to
     * [UnknownDeviceState] so engine-only tests need not supply one; conditions
     * then read null and evaluate false.
     */
    val deviceState: DeviceState get() = UnknownDeviceState

    /** Optional handle to enable/disable other macros at runtime, or null. */
    val macroControl: MacroControl? get() = null

    fun log(message: String)
}
