package com.example.ottomatic.engine.trigger

import com.example.ottomatic.core.model.NodeId
import com.example.ottomatic.core.trigger.TriggerBus
import com.example.ottomatic.core.trigger.TriggerEvent
import com.example.ottomatic.core.trigger.TriggerSource
import com.example.ottomatic.domain.model.GeofencePlace
import kotlinx.coroutines.flow.Flow

/**
 * Bridge between the pure-Kotlin [Trigger] implementations in `engine/` and
 * the Android-backed sources in `data/`.
 *
 * Implementations live in `data/` and supply real system streams. Triggers
 * call these methods inside [Trigger.activate] to obtain their event flow.
 */
@Suppress("TooManyFunctions") // One member per platform capability a trigger can reach; the platform sets the count.
interface TriggerHost {

    /** Stream of all events pushed into [TriggerBus]. Filter by source/node. */
    fun busEvents(): Flow<com.example.ottomatic.core.trigger.TriggerEvent> = TriggerBus.events

    /**
     * Arms a periodic schedule that emits a bus event for [nodeId] every
     * [intervalMinutes] (clamped to the WorkManager 15-minute floor by the
     * implementation).
     *
     * Returns a [ScheduleHandle] whose [ScheduleHandle.cancel] tears the
     * schedule down when the trigger flow is cancelled.
     */
    fun armSchedule(
        nodeId: NodeId,
        intervalMinutes: Long,
    ): ScheduleHandle

    /**
     * Arms a **one-shot** exact alarm that emits a bus event for [nodeId] at
     * [atEpochMs]. Unlike [armSchedule] this is minute-accurate and fires
     * through doze, which is what makes "at 07:30" mean 07:30; the caller
     * re-arms the next occurrence after each event.
     *
     * On Android 12+ exact alarms require the `SCHEDULE_EXACT_ALARM` permission.
     * When the user has not granted it the implementation degrades to an
     * inexact alarm rather than failing, so the trigger still fires — just not
     * to the minute.
     *
     * Returns a [ScheduleHandle] whose [ScheduleHandle.cancel] cancels the
     * pending alarm when the trigger flow is cancelled.
     */
    fun armAlarm(
        nodeId: NodeId,
        atEpochMs: Long,
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
    /**
     * Looks up a stored [GeofencePlace] by id, or null when the place has been
     * deleted (or was never chosen).
     *
     * The place library is user data rather than a platform capability, but it
     * reaches the trigger through the host for the same reason everything else
     * does: [Trigger.activate] is handed a host and nothing else, and the
     * library lives in `data/` where the geofencing client already is.
     *
     * The default returns null so a trigger with no library behind it simply
     * stays unarmed — which is what test doubles want.
     */
    fun geofencePlace(id: String): GeofencePlace? = null

    /**
     * The number the contact [lookupKey] names can be reached on, or null.
     *
     * Reaches the trigger through the host for the reason [geofencePlace] does:
     * [Trigger.activate] is handed a host and nothing else, and the address book
     * lives on the Android side.
     *
     * The default returns null, so a trigger with no address book behind it matches
     * nothing — fail closed, because a sender filter that silently widened to "any
     * sender" would run the macro on every text from anyone, which is far worse than
     * not running at all.
     */
    fun contactNumber(lookupKey: String): String? = null

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
     * Multicast stream of raw samples from [kind], delivered at *at least*
     * [rate].
     *
     * Cold and reference-counted: the platform listener is registered on first
     * collection and unregistered when the last collector goes away, so an
     * unarmed macro costs nothing. Every subscriber of one sensor shares a
     * single registration, running at the fastest rate any of them asked for —
     * which is why a detector must derive all of its timing from
     * [SensorSample.elapsedMs] and never from a sample count. A detector tuned
     * at 50 Hz has to behave identically when a tap trigger drags the same
     * accelerometer to 200 Hz.
     *
     * Unlike the `arm*` methods this returns its values **in band** rather than
     * through [TriggerBus], matching [appLifecycleEvents] and [variableChanges].
     * The bus is deliberately avoided here: it is `replay = 0`,
     * `extraBufferCapacity = 64` and `tryEmit`, so at 200 Hz it would drop
     * samples on any collector stall — and every armed trigger in the process
     * would wake to run its filter chain two hundred times a second.
     *
     * The default is empty, so a device that lacks the sensor — or a test
     * double that does not care — simply leaves the trigger silent, the same
     * way an unresolvable geofence place leaves `trigger.geofence` unarmed.
     */
    fun sensorSamples(kind: SensorKind, rate: SensorRate): Flow<SensorSample> =
        kotlinx.coroutines.flow.emptyFlow()

    /**
     * Arms the one-shot significant-motion sensor for [nodeId], emitting a bus
     * event (source `HARDWARE`, `triggerType = "significant_motion"`) each time
     * it fires and re-arming itself afterwards.
     *
     * This is the one motion signal that costs nothing to leave armed: the
     * detection runs in the sensor hub rather than on the CPU, and it is a
     * wake-up sensor, so it fires through suspend without anyone holding a wake
     * lock. The trade is that it reports only "the device has started moving
     * somewhere" — no direction, no magnitude, and a latency of seconds.
     *
     * Returns a [ScheduleHandle] whose [ScheduleHandle.cancel] disarms it when
     * the trigger flow is cancelled.
     */
    fun armSignificantMotion(nodeId: NodeId): ScheduleHandle = ScheduleHandle { }

    /**
     * Registers this collector's interest in accelerometer gestures continuing
     * to work while the screen is off.
     *
     * The policy is process-wide rather than per-node — there is one
     * accelerometer and one wake lock — so the strongest [mode] any live
     * subscriber asked for wins. Cancel the returned handle to withdraw the
     * interest; when the last one goes, the wake lock is released.
     *
     * Calling this with [ScreenOffMode.NEVER] is deliberately not a no-op at the
     * call site: a trigger arms its choice unconditionally and cancels it in a
     * `finally`, so there is no path on which an interest outlives its trigger.
     */
    fun armScreenOffSensing(mode: ScreenOffMode): ScheduleHandle = ScheduleHandle { }

    /**
     * The largest value [kind] can report, or null when the device has no such
     * sensor.
     *
     * Needed because a proximity reading is only meaningful relative to its own
     * range: most phone sensors are effectively binary, reporting 0 when covered
     * and this value when clear, and that value is 3 cm on some devices and
     * 100 cm on others. A fixed "covered" threshold would read *clear* as
     * *covered* on the short-range ones.
     */
    fun sensorMaximumRange(kind: SensorKind): Float? = null

    /**
     * Stream of variable-change events ([TriggerSource.VARIABLE]) for the variable
     * [name] identifies. The default implementation is empty; the Android-backed
     * implementation reads from a process-wide variable store.
     *
     * The host is process-global and the store is keyed by scope, so what reaches
     * here is a **store key**, not the ref the trigger was configured with —
     * [BoundTriggerHost] translates one into the other per arm and puts the
     * variable's display name back into the payload.
     *
     * Payload contract:
     * - `name` — the variable's name
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
