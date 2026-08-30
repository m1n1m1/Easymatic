package io.github.m1n1m1.easymatic.data.trigger

import io.github.m1n1m1.easymatic.core.model.NodeId
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import io.github.m1n1m1.easymatic.core.trigger.TriggerEvent
import io.github.m1n1m1.easymatic.core.trigger.TriggerSource
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Bridges Android app-lifecycle and UI-mode signals to the engine via a
 * [SharedFlow] of [TriggerEvent] (source [TriggerSource.APP]).
 *
 * Emits an `"init"` event once at construction (the app is up) and `"mode"`
 * events whenever the device's UI / night mode changes, detected through a
 * [ComponentCallbacks2] registered on the application context.
 *
 * Consumed by [AndroidTriggerHost.appLifecycleEvents] and, in turn, by the
 * `trigger.mode_change` and `trigger.app_init` engine triggers.
 */
class AppLifecycleBridge(context: Context) {

    private val appContext = context.applicationContext

    private val _events = MutableSharedFlow<TriggerEvent>(
        replay = 0,
        extraBufferCapacity = DEFAULT_BUFFER,
    )

    val events: SharedFlow<TriggerEvent> = _events.asSharedFlow()

    private val callbacks = object : ComponentCallbacks2 {
        override fun onConfigurationChanged(newConfig: Configuration) {
            val isNight = newConfig.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                Configuration.UI_MODE_NIGHT_YES
            val mode = if (isNight) "night" else "normal"
            _events.tryEmit(
                TriggerEvent(
                    source = TriggerSource.APP,
                    triggerNodeId = NodeId.BROADCAST,
                    payload = mapOf(
                        "event" to "mode",
                        "mode" to mode,
                        "timestamp" to System.currentTimeMillis().toString(),
                    ),
                ),
            )
        }

        override fun onLowMemory() = Unit
        override fun onTrimMemory(level: Int) = Unit
    }

    init {
        // Signal that the app has initialised.
        _events.tryEmit(
            TriggerEvent(
                source = TriggerSource.APP,
                triggerNodeId = NodeId.BROADCAST,
                payload = mapOf(
                    "event" to "init",
                    "timestamp" to System.currentTimeMillis().toString(),
                ),
            ),
        )
        appContext.registerComponentCallbacks(callbacks)
    }

    companion object {
        private const val DEFAULT_BUFFER = 64
    }
}
