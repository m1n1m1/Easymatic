package com.example.ottomatic.engine

import com.example.ottomatic.core.service.SystemServices

/**
 * Default [ExecutionContext] wiring core-provided services to the engine.
 * Pure Kotlin — depends only on `core/` types. The [logger] is injected so
 * the app can route logs to Logcat without engine importing Android.
 */
class DefaultExecutionContext(
    override val systemServices: SystemServices,
    private val logger: (String) -> Unit = {},
) : ExecutionContext {
    override fun log(message: String) = logger(message)
}
