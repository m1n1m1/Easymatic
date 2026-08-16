package com.example.ottomatic.engine.trigger

import com.example.ottomatic.core.model.NodeId
import com.example.ottomatic.core.service.LogLevel
import com.example.ottomatic.core.trigger.TriggerBus
import com.example.ottomatic.core.trigger.TriggerEvent
import com.example.ottomatic.core.trigger.TriggerSource
import com.example.ottomatic.domain.model.GeofencePlace
import com.example.ottomatic.domain.model.MailAccount
import com.example.ottomatic.domain.model.NfcTag
import com.example.ottomatic.domain.model.SmartHomeHub
import com.example.ottomatic.domain.model.WorkflowNode
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
     * Bus events, preceded by any that were held for [nodeId] while nothing was
     * collecting — see [TriggerBus.emitOrHold].
     *
     * For a trigger whose platform source keeps firing after the process dies: a
     * geofence transition can arrive at a manifest receiver seconds before the
     * engine it is meant for has finished starting, and on a bus with `replay = 0`
     * that event was simply lost.
     *
     * Defaults to [busEvents] rather than to `TriggerBus.eventsFor` so a test
     * double that serves its own bus — usually an empty flow, so a collection
     * terminates — keeps serving it. Only the real host overrides this.
     */
    fun busEventsFor(nodeId: NodeId): Flow<TriggerEvent> = busEvents()

    /**
     * Writes [message] into the console of the workflow arming against this host,
     * attributed to [node].
     *
     * [Trigger.activate] is handed a host and nothing else — deliberately, since a
     * trigger describes an event source and not the graph that wants it — so this
     * is the only route from an arming trigger to a run log. Without it a trigger
     * that cannot arm, or that the platform refused, has no way to say so: the
     * failures land in Logcat if anywhere, and "why won't this run" is a question
     * asked in the editor.
     *
     * The default is a no-op, so an unbound host and a test double stay silent.
     * [BoundTriggerHost] is where the real one lives, because [WorkflowRunner] is
     * the one place that activates triggers *and* holds both the workflow id and
     * the [com.example.ottomatic.engine.ExecutionContext].
     */
    fun report(node: WorkflowNode, message: String, level: LogLevel = LogLevel.INFO) {
        // No-op: an unbound host has no workflow to attribute this to.
    }

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
     * The saved [NfcTag] with this hardware id, or null when no name was ever given
     * to it.
     *
     * Reaches the trigger through the host for the reason [geofencePlace] does, but
     * with one difference worth knowing: null here is **not** a failure. A tag
     * trigger matches on the id a tap carries, so an unnamed tag still fires it —
     * all that is missing is a friendly name to put on the output. That is why
     * nothing validates a tag reference, where a missing place leaves a geofence
     * watching nowhere.
     */
    fun nfcTag(uid: String): NfcTag? = null

    /**
     * Whether NFC tag scanning can work on this phone at all.
     *
     * Two states no permission check reports — no chip, and tag intents switched
     * off for this app in Android's own NFC settings — plus [NfcStatus.UNKNOWN],
     * which is what a host with no platform behind it answers so a test double
     * does not make every armed trigger complain.
     */
    fun nfcStatus(): NfcStatus = NfcStatus.UNKNOWN

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
     * [onResult] is invoked once the platform has an answer, which is **after
     * this method returns** — registration is asynchronous, so the return value
     * says only that the request was made. Before it existed, a refused
     * registration (location switched off, background location not granted, too
     * many fences) produced a macro that looked armed, a console with nothing in
     * it, and one line in Logcat nobody was reading.
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
        onResult: (GeofenceArmResult) -> Unit = {},
    ): ScheduleHandle

    /**
     * Starts the "been away long enough" countdown for [nodeId]: in
     * [awayDelayMs] from now, push a bus event with source `GEOFENCE`,
     * `triggerNodeId = nodeId` and `event = "away"` — unless
     * [cancelGeofenceAway] gets there first.
     *
     * **Not a parameter of [armGeofence], because it is not a property of the
     * fence.** Play Services has no "dwell outside" transition — its DWELL is
     * loitering *inside* — so this is an ordinary alarm, and it starts when the
     * exit arrives rather than when the fence is registered.
     *
     * **And not tied to the trigger flow's lifetime either.** `GeofenceReceiver`
     * asks the engine to re-arm on every transition, so a countdown cancelled in
     * the flow's `finally` would be torn down by the very exit that started it.
     * Being away is a fact about the world, not about whether the engine happened
     * to restart in the middle. A countdown left behind by a macro that was
     * switched off is harmless: the event reaches a node that is not collecting
     * and expires off the bus, or reaches one whose away switch is now off and is
     * filtered out. Only an enter cancels it.
     *
     * The default is a no-op, so a host with no platform behind it simply never
     * fires the away half — the same shape [geofencePlace] uses.
     */
    fun armGeofenceAway(nodeId: NodeId, awayDelayMs: Long) = Unit

    /** Stops [nodeId]'s away countdown; the device is back inside the fence. */
    fun cancelGeofenceAway(nodeId: NodeId) = Unit

    /**
     * Where [nodeId]'s fence last saw the device, for [GeofenceGate] to judge the
     * next transition against.
     *
     * **Persisted rather than held in memory**, and that is the whole point of it
     * reaching through the host at all. The artefact this exists to suppress —
     * Play Services re-announcing "you are outside" after [armGeofence]
     * re-registered the fence — happens *because* the process died and came back,
     * so a belief that died with it would be gone exactly when it was needed.
     *
     * Nothing forgets it, on `MailSeenStore`'s reasoning: a disarm and a re-arm are
     * indistinguishable from the trigger's `finally`, so clearing on teardown would
     * reopen the hole after every graph edit. It survives a reboot too, which is
     * strictly *more* accurate than clearing — switched off at work and back on at
     * home leaves this at [GeofencePresence.INSIDE], so the exit that follows is a
     * real state change and correctly fires.
     *
     * The default answers [GeofencePresence.UNKNOWN], which is the state that
     * believes the platform. A host with no store behind it therefore behaves
     * exactly as everything did before the gate existed, which is what a test
     * double wants.
     */
    fun geofencePresence(nodeId: NodeId): GeofencePresence = GeofencePresence.UNKNOWN

    /** Records where [nodeId] now believes it is. See [geofencePresence]. */
    fun recordGeofencePresence(nodeId: NodeId, presence: GeofencePresence) = Unit

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
     * The [MailAccount] with this id, or null when it was deleted or never chosen.
     *
     * Reaches the trigger through the host for [geofencePlace]'s reason, but null
     * means something different here than it does for [nfcTag], and the difference
     * decides what the trigger does about it. A missing tag costs a firing macro
     * its friendly name and nothing else; a missing *account* is the host, the
     * username and the password all at once, so there is nothing to connect to.
     * `trigger.mail` therefore stays unarmed and says so, the way an unresolvable
     * geofence place does.
     */
    fun mailAccount(id: String): MailAccount? = null

    /**
     * Watches [accountId]'s mailbox for [nodeId], emitting a bus event (source
     * `MAIL`, `triggerNodeId = nodeId`) for each newly arrived message.
     *
     * Two mechanisms behind one call, deliberately not two modes. The substrate is
     * a WorkManager poll clamped to the 15-minute floor, which is what makes this
     * work from a killed app and across a reboot. IMAP IDLE is layered on top when
     * the server offers it, cutting latency from minutes to seconds — and the poll
     * is **not torn down** when IDLE comes up, only slowed, because a socket
     * dropped by carrier NAT stays parked in IDLE believing it is healthy and
     * nothing else would ever notice. Which one is live is reported into the
     * node's own console.
     *
     * Interest is reference-counted per **account**, not per node: several nodes
     * may watch one inbox and must cost one connection between them, which is the
     * contract [sensorSamples] states for a single sensor registration.
     *
     * [onReport] carries a line back to the macro's own console, and exists for
     * [armGeofence]'s `onResult` reason: everything interesting here happens
     * *after* this method returns — a server with no IDLE, a connection that keeps
     * dropping, a mailbox the server renumbered — and none of it has anywhere else
     * to be said. It may be called from a background thread at any time until the
     * handle is cancelled.
     *
     * Returns a [ScheduleHandle] whose [ScheduleHandle.cancel] withdraws this
     * node's interest when the trigger flow is cancelled. The default is a no-op,
     * so a host with no mail behind it leaves the trigger silent.
     */
    fun armMailWatch(
        nodeId: NodeId,
        accountId: String,
        spec: MailWatchSpec,
        onReport: (String, LogLevel) -> Unit = { _, _ -> },
    ): ScheduleHandle = ScheduleHandle { }

    /**
     * The next appointment [spec] describes at or after [afterEpochMs], or null when there
     * is nothing inside the look-ahead horizon.
     *
     * **A lookup rather than a registration**, on [geofencePlace]'s and [mailAccount]'s
     * shape: everything it answers is one query away, and it is asked at arm time, after
     * every fire and after every calendar change. Keeping it a lookup is what leaves the
     * re-arming loop in `engine/`, beside `ScheduleTrigger.alarmFlow`'s, where it is
     * testable against a fake host with no calendar provider anywhere.
     *
     * The split is not arbitrary. The *policy* — which occurrences count, where the offset
     * comes from, what "nothing found" means — is macro logic and belongs where macro
     * logic is tested. The *platform* half reduces to one query, which is exactly the
     * shape the other lookups here already have.
     *
     * **Null is not "never".** For a schedule, no next fire time means the trigger is
     * finished; for a calendar it means only that nothing is in the diary *yet*, and
     * somebody may add something in ten minutes. The caller re-checks rather than
     * stopping — see `CalendarEventTrigger`.
     *
     * Suspending, unlike every other lookup here, because it is a provider round trip
     * rather than a map read. It is only ever called from inside a trigger's flow, which
     * has somewhere to suspend.
     */
    suspend fun nextCalendarOccurrence(spec: CalendarWatchSpec, afterEpochMs: Long): CalendarOccurrence? = null

    /**
     * Registers this node's interest in *any* change to the phone's calendars, emitting a
     * bus event (source `CALENDAR`, `triggerNodeId = nodeId`) when one happens.
     *
     * [armMailWatch]'s shape and its reference counting: one observer serves the process
     * however many nodes are armed. It is registered on the **first** arm rather than at
     * start-up, and that is not tidiness — registering it needs `READ_CALENDAR`, so a
     * registration taken in `ServiceLocator.init` would be attempted before the user has
     * granted anything and would never recover without a process restart.
     *
     * The events are **coalesced**, because the provider notifies once per row it touches:
     * an account sync that pulls twelve appointments fires it twelve times, and a macro
     * that posts a notification on "calendar changed" would post twelve.
     *
     * Used by both calendar triggers, which is the part worth knowing: `trigger.calendar_changed`
     * is *about* the change, and `trigger.calendar_event` needs it because an appointment
     * moved after its alarm was armed would otherwise fire at the old time, silently.
     *
     * [onReport] carries a line back to the macro's own console, for [armMailWatch]'s
     * reason. The default is a no-op, so a host with no calendar behind it leaves the
     * trigger silent.
     */
    fun armCalendarWatch(
        nodeId: NodeId,
        onReport: (String, LogLevel) -> Unit = { _, _ -> },
    ): ScheduleHandle = ScheduleHandle { }

    /**
     * Registers this node's interest in a Home Assistant entity or event type.
     *
     * [armMailWatch]'s shape, with one difference that shows through here: this does
     * **not** open a connection on the node's behalf. The socket is held for every
     * configured hub as long as the engine runs, independent of what is armed — see
     * [com.example.ottomatic.core.service.HubLink] for why — so arming is only a row in
     * a routing table, and disarming only removes it.
     *
     * That routing table is the reason this exists at all rather than the trigger
     * filtering the bus itself: `TriggerEvent` carries **one** node id, and a busy
     * install emits hundreds of `state_changed` a minute. Broadcasting them would wake
     * every armed trigger in the process to run its own filter chain, which is what
     * [sensorSamples]' KDoc refuses to do for the same reason.
     *
     * [onReport] carries a line back to the macro's own console, for [armMailWatch]'s
     * reason: a token that stopped being accepted, a server that keeps dropping the
     * socket, an instance that was upgraded — all of it happens *after* this returns and
     * has nowhere else to be said. It may be called from a background thread at any time
     * until the handle is cancelled.
     *
     * The default is a no-op, so a host with no Home Assistant behind it leaves the
     * trigger silent rather than failing to arm.
     */
    fun armHomeAssistantWatch(
        nodeId: NodeId,
        spec: HaWatchSpec,
        onReport: (String, LogLevel) -> Unit = { _, _ -> },
    ): ScheduleHandle = ScheduleHandle { }

    /**
     * Registers [nodeId]'s interest in an MQTT topic filter, and answers the handle that
     * drops it.
     *
     * [armHomeAssistantWatch]'s twin in every respect, including the reason it exists at
     * all: `TriggerEvent` carries one node id, one connection is shared by every armed
     * node, and a busy broker publishes constantly — so the routing has to happen in
     * `data/` rather than every trigger filtering a broadcast.
     *
     * Where the two differ is what the handle costs. A Home Assistant watch is a
     * subscription the server evaluates; this one is an MQTT subscription, refcounted per
     * *filter*, so the last node to let go of `zigbee2mqtt/+/action` is what actually
     * unsubscribes it.
     *
     * [onReport] carries a line back to the macro's own console, on [armMailWatch]'s
     * reason: a password the broker stopped accepting, an address that has moved, a
     * connection that keeps dropping — all of it happens *after* this returns and has
     * nowhere else to be said. It may be called from a background thread at any time until
     * the handle is cancelled.
     *
     * The default is a no-op, so a host with no broker behind it leaves the trigger silent
     * rather than failing to arm.
     */
    fun armMqttWatch(
        nodeId: NodeId,
        spec: MqttWatchSpec,
        onReport: (String, LogLevel) -> Unit = { _, _ -> },
    ): ScheduleHandle = ScheduleHandle { }

    /**
     * The smart-home hub [id] names, or null when it was never chosen or has been
     * deleted.
     *
     * A library lookup on [mailAccount]'s shape and for its reason: a trigger has to be
     * able to say *"the hub this points at is gone"* into the macro's own console rather
     * than arming a watch on nothing and looking, from outside, exactly like a macro
     * that is simply waiting.
     */
    fun smartHomeHub(id: String): SmartHomeHub? = null

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

/**
 * What Play Services said about a geofence registration, once it had an answer.
 *
 * Reported through [TriggerHost.armGeofence]'s `onResult` rather than returned,
 * because the platform answers asynchronously — the request is made, the trigger
 * starts collecting, and the verdict arrives later.
 */
sealed interface GeofenceArmResult {

    /** The platform is now watching this fence. */
    data object Registered : GeofenceArmResult

    /** The platform refused. [message] is the GMS status string for [code]. */
    data class Refused(val code: Int, val message: String) : GeofenceArmResult
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
