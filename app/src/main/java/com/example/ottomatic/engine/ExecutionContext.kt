package com.example.ottomatic.engine

import com.example.ottomatic.core.service.SystemServices

/**
 * Context available to an [Action] while executing.
 *
 * Provides access to shared infrastructure without pulling Android types
 * into `engine/`: logging and system services.
 */
interface ExecutionContext {

    val systemServices: SystemServices

    fun log(message: String)
}
