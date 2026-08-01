package com.example.ottomatic.engine

import com.example.ottomatic.core.service.DeviceState
import com.example.ottomatic.core.service.MacroControl
import com.example.ottomatic.core.service.SystemServices
import com.example.ottomatic.core.service.UnknownDeviceState
import com.example.ottomatic.engine.trigger.NoSensors
import com.example.ottomatic.engine.trigger.SensorReader

/**
 * Default [ExecutionContext] wiring core-provided services to the engine.
 * Pure Kotlin — depends only on `core/` types and the engine's own sensor port.
 * The [logger] is injected so the app can route logs to Logcat without engine
 * importing Android.
 */
class DefaultExecutionContext(
    override val systemServices: SystemServices,
    override val macroControl: MacroControl? = null,
    override val deviceState: DeviceState = UnknownDeviceState,
    override val sensors: SensorReader = NoSensors,
    private val logger: (String) -> Unit = {},
) : ExecutionContext {
    override fun log(message: String) = logger(message)
}
