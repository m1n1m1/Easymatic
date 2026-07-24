package com.example.ottomatic.core.service

/**
 * Allows an action to enable or disable another macro at runtime.
 *
 * Implemented in `data/` on top of the [com.example.ottomatic.engine.service.MacroEngineService]
 * enable/disable intents and `WorkflowRepository.setEnabled`. Exposed to
 * actions via [com.example.ottomatic.engine.ExecutionContext.macroControl]
 * (nullable so engine-only tests need not supply it).
 *
 * Each call is fire-and-forget: the persistence and arming happen
 * asynchronously; `success` reports only that the request was dispatched.
 */
interface MacroControl {

    /** Persists `enabled=true` and arms [macroId]'s triggers. */
    fun enable(macroId: String): Boolean

    /** Persists `enabled=false` and disarms [macroId]'s triggers. */
    fun disable(macroId: String): Boolean
}
