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

    /**
     * Arms a periodic battery-level poll for [nodeId]. Emits a bus event
     * (source `BATTERY`, payload `event = "level_poll"`) when the level
     * crosses [threshold] in [direction] (`"above"` or `"below"`).
     *
     * Polling is WorkManager-backed and clamped to the 15-minute floor, so
     * it runs even when the app is killed. Hysteresis is applied so a level
     * hovering near the threshold does not flap — an event is emitted only on
     * the transition into the satisfied state.
     *
     * Returns a [ScheduleHandle] whose [ScheduleHandle.cancel] tears the
     * poll down when the trigger flow is cancelled.
     */
    fun armBatteryLevelPoll(
        nodeId: String,
        intervalMinutes: Long,
        direction: String,
        threshold: Int,
    ): ScheduleHandle
}

/** Allows a trigger to tear down its armed schedule on flow cancellation. */
fun interface ScheduleHandle {
    fun cancel()
}
