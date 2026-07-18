package com.example.ottomatic.engine.trigger

import com.example.ottomatic.core.trigger.TriggerBus
import com.example.ottomatic.core.trigger.TriggerSource
import kotlinx.coroutines.flow.Flow

/**
 * Bridge between the pure-Kotlin [Trigger] implementations in `engine/` and
 * the Android-backed sources in `data/`.
 *
 * Implementations live in `data/` and supply real system streams. Triggers
 * call these methods inside [Trigger.activate] to obtain their event flow.
 */
interface TriggerHost {

    /** Stream of all events pushed into [TriggerBus]. Filter by source/node. */
    fun busEvents(): Flow<com.example.ottomatic.core.trigger.TriggerEvent> = TriggerBus.events

    /**
     * Arms a periodic schedule that emits a bus event for [nodeId] every
     * [intervalMinutes] (clamped to the WorkManager 15-minute floor by the
     * implementation). When [cron] is non-null the implementation interprets
     * it as a cron expression instead of a fixed interval.
     *
     * Returns a [ScheduleHandle] whose [ScheduleHandle.cancel] tears the
     * schedule down when the trigger flow is cancelled.
     */
    fun armSchedule(
        nodeId: String,
        intervalMinutes: Long,
        cron: String?,
    ): ScheduleHandle
}

/** Allows a trigger to tear down its armed schedule on flow cancellation. */
fun interface ScheduleHandle {
    fun cancel()
}
