package com.example.ottomatic.engine.trigger

import com.example.ottomatic.core.model.NodeId
import com.example.ottomatic.core.trigger.TriggerBus
import com.example.ottomatic.core.trigger.TriggerEvent
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
        nodeId: NodeId,
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
        nodeId: NodeId,
        intervalMinutes: Long,
        direction: BatteryDirection,
        threshold: Int,
    ): ScheduleHandle

    /**
     * Arms a geofence for [nodeId] centred at ([latitude], [longitude]) with a
     * radius of [radiusMeters] metres. When the device undergoes any of the
     * transitions in [transitions] (enter / exit / dwell), the implementation
     * pushes a bus event with source `GEOFENCE` and `triggerNodeId = nodeId`.
     *
     * [dwellDelayMs] is forwarded to the platform as the loitering delay
     * (only effective when [transitions] contains [GeofenceTransition.DWELL]).
     *
     * Geofences are monitored by the OS in the background and persist across
     * app process death and device reboot, so the manifest-registered
     * `GeofenceReceiver` keeps firing without the runner being active.
     *
     * Returns a [ScheduleHandle] whose [ScheduleHandle.cancel] removes the
     * geofence when the trigger flow is cancelled.
     */
    @Suppress("LongParameterList") // Mirrors the GMS Geofence.Builder API surface.
    fun armGeofence(
        nodeId: NodeId,
        latitude: Double,
        longitude: Double,
        radiusMeters: Float,
        transitions: Set<GeofenceTransition>,
        dwellDelayMs: Int = DEFAULT_DWELL_DELAY_MS,
    ): ScheduleHandle

    /**
     * Stream of engine-internal macro lifecycle events
     * ([TriggerSource.MACRO]). Emits when a macro is enabled (its
     * [com.example.ottomatic.engine.WorkflowRunner.run] starts) and when a
     * triggered execution finishes. The default implementation reads from the
     * process-wide [MacroEventBus] so no Android backing is required.
     *
     * Payload contract:
     * - `event` ∈ `"enabled"`, `"finished"`
     * - `macroId` — the workflow id
     */
    fun macroLifecycleEvents(): Flow<TriggerEvent> = MacroEventBus.events

    /**
     * Stream of app-lifecycle events ([TriggerSource.APP]) — app initialisation
     * and device/UI mode changes. The default implementation is empty; the
     * Android-backed implementation wires a [android.app.UiModeManager] listener
     * and the app initialisation signal.
     *
     * Payload contract:
     * - `event` ∈ `"init"`, `"mode"`
     * - `mode` (only for `mode`) — the new night-mode state (`"normal"` / `"night"`)
     */
    fun appLifecycleEvents(): Flow<TriggerEvent> = kotlinx.coroutines.flow.emptyFlow()

    /**
     * Stream of variable-change events ([TriggerSource.VARIABLE]) for the
     * variable named [name]. The default implementation is empty; the
     * Android-backed implementation reads from a process-wide variable store.
     *
     * Payload contract:
     * - `name` — the variable name
     * - `value` — the new string value
     */
    fun variableChanges(name: String): Flow<TriggerEvent> = kotlinx.coroutines.flow.emptyFlow()
}

/** Geofence transition kinds, mirroring `com.google.android.gms.location.Geofence`. */
enum class GeofenceTransition {
    ENTER,
    EXIT,
    DWELL,
}

/** Default loitering delay (ms) forwarded to the platform when DWELL is armed. */
const val DEFAULT_DWELL_DELAY_MS = 30_000

/** Allows a trigger to tear down its armed schedule on flow cancellation. */
fun interface ScheduleHandle {
    fun cancel()
}
