package com.example.ottomatic.engine

import com.example.ottomatic.core.service.MacroControl
import com.example.ottomatic.core.service.SystemServices

/**
 * Context available to an [Action] while executing.
 *
 * Provides access to shared infrastructure without pulling Android types
 * into `engine/`: logging, system services and (optionally) macro control.
 *
 * [macroControl] is nullable: engine-only unit tests and environments without
 * a running [com.example.ottomatic.engine.service.MacroEngineService] may
 * leave it `null`; actions that require it should degrade gracefully.
 */
interface ExecutionContext {

    val systemServices: SystemServices

    /** Optional handle to enable/disable other macros at runtime, or null. */
    val macroControl: MacroControl? get() = null

    fun log(message: String)
}
