package com.example.ottomatic.engine

import com.example.ottomatic.core.service.DeviceState
import com.example.ottomatic.core.service.MacroControl
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

    /** Optional handle to enable/disable other macros at runtime, or null. */
    val macroControl: MacroControl? get() = null

    fun log(message: String)
}
