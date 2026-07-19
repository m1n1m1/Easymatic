package com.example.ottomatic.core.trigger

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Identifies which Android source produced a [TriggerEvent].
 * Triggers filter the bus by this tag plus their node id.
 */
enum class TriggerSource {
    MANUAL,
    SCHEDULE,
    SMS,
    NOTIFICATION,
    BATTERY,
    BOOT,
    GEOFENCE,
}

/**
 * Payload produced when a trigger fires. Pushed into [TriggerBus] by
 * receivers/workers/services in `data/`, and consumed by [Trigger]
 * implementations in `engine/`.
 */
data class TriggerEvent(
    val source: TriggerSource,
    val triggerNodeId: String,
    val payload: Map<String, String> = emptyMap(),
    val firedAtEpochMs: Long = System.currentTimeMillis(),
)

/**
 * Process-wide bus connecting Android system callbacks to the workflow engine.
 *
 * Receivers, workers and services call [emit]; trigger implementations
 * subscribe to [events] and filter by source/node.
 *
 * replay = 0 so a freshly-subscribed trigger does not replay stale events.
 */
object TriggerBus {

    private val _events = MutableSharedFlow<TriggerEvent>(
        replay = 0,
        extraBufferCapacity = DEFAULT_BUFFER,
    )

    val events: SharedFlow<TriggerEvent> = _events.asSharedFlow()

    fun emit(event: TriggerEvent) {
        _events.tryEmit(event)
    }

    private const val DEFAULT_BUFFER = 64
}
