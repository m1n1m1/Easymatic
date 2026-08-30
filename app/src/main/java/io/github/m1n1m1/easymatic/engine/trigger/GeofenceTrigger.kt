package io.github.m1n1m1.easymatic.engine.trigger

import io.github.m1n1m1.easymatic.core.model.NodeTypeId
import io.github.m1n1m1.easymatic.core.permissions.PermissionRequirement
import io.github.m1n1m1.easymatic.core.permissions.Permissions
import io.github.m1n1m1.easymatic.core.permissions.PrerequisiteType
import io.github.m1n1m1.easymatic.core.service.LogLevel
import io.github.m1n1m1.easymatic.core.trigger.TriggerBus
import io.github.m1n1m1.easymatic.core.trigger.TriggerSource
import io.github.m1n1m1.easymatic.domain.model.GeofencePlace
import io.github.m1n1m1.easymatic.domain.model.NodeCategory
import io.github.m1n1m1.easymatic.domain.model.NodeIcon
import io.github.m1n1m1.easymatic.domain.model.WorkflowNode
import io.github.m1n1m1.easymatic.domain.model.config.Label
import io.github.m1n1m1.easymatic.domain.model.config.Picker
import io.github.m1n1m1.easymatic.domain.model.config.PickerKind
import io.github.m1n1m1.easymatic.domain.model.config.VisibleWhen
import io.github.m1n1m1.easymatic.domain.model.dataOut
import io.github.m1n1m1.easymatic.domain.model.items.GeofenceEvent
import io.github.m1n1m1.easymatic.engine.NodeOutput
import io.github.m1n1m1.easymatic.engine.triggerNode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.Serializable

/** Default for [GeofenceConfig.awayMinutes] — half an hour out of the house. */
const val DEFAULT_AWAY_MINUTES = 30

/**
 * Config for `trigger.geofence`.
 *
 * [placeId] references a [io.github.m1n1m1.easymatic.domain.model.GeofencePlace] in
 * the shared library rather than carrying coordinates of its own: the same
 * place is usually watched by several macros, and one edit should move all of
 * them. A blank id (or one whose place has been deleted) means "unconfigured",
 * and the trigger is not armed — the same failure mode the old null coordinates
 * had.
 *
 * The radius belongs to the place, not here. The armed transitions are four
 * independent switches rather than a comma-joined enum string, which could
 * express combinations the UI never offered (and vice versa).
 *
 * **[onAway] is the mirror image of [onDwell] and the platform has no such
 * transition.** `GEOFENCE_TRANSITION_DWELL` is loitering *inside* a fence, and
 * `setLoiteringDelay` is the enter→dwell gap; there is nothing that means
 * "outside for a while". So the away half is an ordinary alarm, started when the
 * exit arrives and cancelled when an enter does — which is one of the two reasons
 * [platformTransitions], [emittedEvents] and [actedOnEvents] are three sets rather
 * than one. The other, and now the larger, is [platformTransitions]' own KDoc.
 *
 * [awayMinutes] is in minutes where [dwellDelayMs] is in milliseconds, and the
 * mismatch is deliberate: a loitering delay is seconds to minutes and is handed
 * to the platform in its own unit, where an away period is tens of minutes and a
 * doze-batched alarm cannot honour sub-minute precision anyway.
 */
@Suppress("LongParameterList") // One property per form field; a config class is a flat declaration.
@Serializable
data class GeofenceConfig(
    @Label("Place") @Picker(PickerKind.GEOFENCE_PLACE) val placeId: String = "",
    @Label("On enter") val onEnter: Boolean = true,
    @Label("On exit") val onExit: Boolean = false,
    @Label("On dwell") val onDwell: Boolean = false,
    @Label("Dwell delay (ms)")
    @VisibleWhen("onDwell", "true")
    val dwellDelayMs: Int = DEFAULT_DWELL_DELAY_MS,
    @Label("On staying away") val onAway: Boolean = false,
    @Label("Away for (minutes)")
    @VisibleWhen("onAway", "true")
    val awayMinutes: Int = DEFAULT_AWAY_MINUTES,
) {
    /**
     * What Play Services is asked to watch — **always both directions**, whatever
     * the switches above say.
     *
     * This used to mirror the switches, widened only for [onAway], and that was
     * wrong the moment [GeofenceGate] started judging a transition against a
     * remembered presence: a belief can only be as good as what the fence is asked
     * to report. A node watching for an exit was never told about arrivals, so its
     * belief latched OUTSIDE and every departure after the first was discarded as a
     * repeat. A node watching only for an enter — which is the *default*
     * configuration — latched INSIDE the same way. Both are "fires once, then never
     * again", and both are invisible until somebody waits a week.
     *
     * So the platform reports which side of the line the device is on, and
     * [emittedEvents] decides what the user hears about. The away half is no longer
     * the exception that made two sets necessary; it is one case of the rule.
     *
     * [GeofenceTransition.DWELL] stays optional because it is not a *direction*: it
     * says nothing about which side of the line we are on that the enter before it
     * did not, and it costs the platform a loitering delay to keep counting.
     */
    val platformTransitions: Set<GeofenceTransition>
        get() = if (onDwell) {
            setOf(GeofenceTransition.ENTER, GeofenceTransition.EXIT, GeofenceTransition.DWELL)
        } else {
            setOf(GeofenceTransition.ENTER, GeofenceTransition.EXIT)
        }

    /**
     * The `event` payload values that reach the `event` port — what the user
     * actually asked for, which is also what the arm-time console line announces.
     */
    val emittedEvents: Set<String>
        get() = buildSet {
            if (onEnter) add(GeofenceTransition.ENTER.payloadValue)
            if (onExit) add(GeofenceTransition.EXIT.payloadValue)
            if (onDwell) add(GeofenceTransition.DWELL.payloadValue)
            if (onAway) add(GeofenceTrigger.EVENT_AWAY)
        }.ifEmpty { setOf(GeofenceTransition.ENTER.payloadValue) }

    /**
     * The events that can change what this node *does* — [emittedEvents] plus the
     * two the away countdown is driven by.
     *
     * It exists to keep the console honest now that every fence watches both
     * directions. A phone sitting at home is told it has arrived on every
     * registration (`setInitialTrigger(INITIAL_TRIGGER_ENTER)`), and each of those is
     * correctly discarded as a repeat — but a persisted console line per arm,
     * explaining that a macro nobody wrote did not run, is a console nobody reads.
     * So a discard is written at INFO only when it could have changed something the
     * user would notice, and at DEBUG otherwise. An exit that would have started an
     * away countdown counts as noticeable even though it is never emitted, which is
     * why this is a third set rather than [emittedEvents] reused.
     */
    val actedOnEvents: Set<String>
        get() = emittedEvents + if (onAway) {
            setOf(GeofenceTransition.ENTER.payloadValue, GeofenceTransition.EXIT.payloadValue)
        } else {
            emptySet()
        }

    /** [awayMinutes] as the millisecond delay the host arms an alarm for. */
    val awayDelayMs: Long get() = awayMinutes.coerceAtLeast(1).toLong() * MILLIS_PER_MINUTE

    /**
     * Whether the user chose anything at all — false is exactly the state the
     * enter fallback above covers, and the one `ConfigFormHint` warns about.
     *
     * It lives here rather than in the editor so the fallback and the sentence
     * describing it cannot disagree: a fifth switch added to the form and not to
     * a list in `feature/` would make a configured node claim to be empty.
     */
    val hasChosenEvent: Boolean get() = onEnter || onExit || onDwell || onAway
}

private const val MILLIS_PER_MINUTE = 60_000L

/**
 * Trigger for `trigger.geofence`. Resolves its configured place through the
 * host, arms a platform geofence at that place's centre and radius when
 * collection starts, surfaces matching bus events, and cancels the geofence
 * when the flow is cancelled.
 *
 * The place is read once, at activation. Editing a place therefore only takes
 * effect on the next arm, which is why the editor asks the engine service to
 * re-arm after a save.
 *
 * Produces a typed [GeofenceEvent] item on the `event` data port.
 *
 * Payload contract with `GeofenceReceiver` in `data/` (string keys):
 * - `event` ∈ `"enter"`, `"exit"`, `"dwell"`, `"away"`
 * - `lat`, `lng`, `accuracy`, `timestamp`
 *
 * **`"away"` carries no location.** The other three are reported by Play
 * Services with the fix that produced them; the away event comes from an alarm,
 * which knows only which node it belongs to. The fallbacks below therefore
 * substitute the *place's* coordinates — the honest answer to "which fence is
 * this about", and deliberately not an answer to "where are you".
 *
 * **Not every transition Play Services sends is a crossing.** A registration resets
 * the fence's state inside Play Services, which then re-evaluates and announces the
 * result — and an announcement of "outside" is delivered as an ordinary EXIT, not as
 * an initial trigger. `armGeofence` used to re-register on every arm, so a macro
 * watching for an exit fired at three in the morning from wherever it was sleeping,
 * on the timer set by whatever was reaping and resurrecting the process that night.
 * Two things answer that, in this order: [GeofenceRegistration] stops an unchanged
 * fence being re-registered at all, and [GeofenceGate] decides which of the
 * transitions that do arrive are real. See both for the argument.
 *
 * One limitation of the away half is worth knowing before it surprises anybody:
 * the countdown is started by a real departure, so a macro armed while you are
 * *already* away starts counting only after your next return and departure. The
 * alternative, asking the platform for an initial exit at registration, delivers
 * a genuine exit broadcast on every re-arm — which would both restart the
 * countdown and fire the `exit` branch of any macro that has one.
 */
class GeofenceTrigger : Trigger<GeofenceConfig, GeofenceEvent> {

    override val definition = triggerNode<GeofenceConfig, GeofenceEvent>(
        typeId = TYPE_ID.value,
        displayName = "Geofence",
        description = "Starts when the device enters, exits or dwells inside a circular area, " +
            "or once it has been away from one for a while",
        category = NodeCategory.LOCATION,
        icon = NodeIcon.LOCATION,
        output = dataOut<GeofenceEvent>("event", label = "Event"),
        // Foreground location gets the fence registered at all; background
        // location is what lets it keep firing once the app is off-screen,
        // which is the only way a geofence macro is ever useful.
        permissions = listOf(
            PermissionRequirement(
                manifestPermission = Permissions.ACCESS_FINE_LOCATION.manifest,
                type = PrerequisiteType.RUNTIME,
                rationaleKey = "geofence.location",
            ),
            PermissionRequirement(
                manifestPermission = Permissions.ACCESS_BACKGROUND_LOCATION.manifest,
                type = PrerequisiteType.RUNTIME,
                rationaleKey = "geofence.backgroundLocation",
            ),
        ),
    )

    /**
     * The place this node watches, or null having said in the console why not.
     *
     * No place chosen, or the chosen one since deleted: nothing to arm. Not
     * arming beats arming a fence at (0, 0) — but staying *silent* about it does
     * not, which is what this used to do. A macro watching nowhere looked exactly
     * like one watching correctly and never seeing anything.
     */
    private fun resolvePlace(config: GeofenceConfig, node: WorkflowNode, host: TriggerHost): GeofencePlace? {
        if (config.placeId.isBlank()) {
            host.report(node, "No place chosen, so this trigger is not watching anywhere", LogLevel.WARN)
            return null
        }
        return host.geofencePlace(config.placeId) ?: run {
            host.report(
                node,
                "The place this trigger points at no longer exists, so it is not watching anywhere",
                LogLevel.WARN,
            )
            null
        }
    }

    /**
     * Starts or stops the away countdown on the two transitions that drive it.
     *
     * Called before the emit filter rather than after it: those transitions are
     * usually *not* events this node publishes, so filtering first would leave
     * the countdown never started.
     */
    private fun driveAwayCountdown(
        config: GeofenceConfig,
        node: WorkflowNode,
        host: TriggerHost,
        event: String,
    ) {
        if (!config.onAway) return
        when (event) {
            GeofenceTransition.EXIT.payloadValue -> host.armGeofenceAway(node.id, config.awayDelayMs)
            GeofenceTransition.ENTER.payloadValue -> host.cancelGeofenceAway(node.id)
        }
    }

    /**
     * Whether this transition is a real crossing, updating what the node believes
     * about where it is on the way through.
     *
     * The judging itself is [GeofenceGate]'s, which is pure and JVM-tested; what
     * belongs here is the three things it cannot reach — the *place*, which is what
     * turns a reported fix into a distance, when this fence was last registered, and
     * the macro's console, which is the only place a discarded transition can be
     * seen from.
     *
     * The console level is [GeofenceConfig.actedOnEvents]', and its argument is
     * there. INFO where the discard could have changed something, because DEBUG
     * lines stay in memory and every one of these happens while the app is closed
     * and the phone is in somebody's pocket; DEBUG for the arrivals a fence now
     * watches purely so the belief can learn from them.
     */
    // The node, its config, its place and its host are four separate things a gate
    // call needs and none of them is derivable from the others.
    @Suppress("LongParameterList")
    private fun crossed(
        config: GeofenceConfig,
        place: GeofencePlace,
        node: WorkflowNode,
        host: TriggerHost,
        payload: Map<String, String>,
        event: String,
    ): Boolean {
        val believed = host.geofencePresence(node.id)
        val latitude = payload[KEY_LATITUDE]?.toDoubleOrNull()
        val longitude = payload[KEY_LONGITUDE]?.toDoubleOrNull()
        val registeredAt = host.geofenceRegisteredAt(node.id)
        val broadcastAt = payload[KEY_TIMESTAMP]?.toLongOrNull()
        val verdict = GeofenceGate.judge(
            event = event,
            believed = believed,
            accuracyMeters = payload[KEY_ACCURACY]?.toFloatOrNull(),
            // Null rather than a substituted centre when the transition carried no
            // location — the away alarm never does — because a distance of zero is a
            // claim about where the phone is, and this one knows nothing.
            distanceMeters = if (latitude != null && longitude != null) {
                place.distanceTo(latitude, longitude)
            } else {
                null
            },
            radiusMeters = place.radiusMeters,
            // The broadcast's own stamp rather than the clock here: the re-arm this
            // transition itself asks for is what put us in this method, so measuring
            // from "now" would measure the wrong registration.
            sinceRegisteredMs = if (registeredAt != null && broadcastAt != null) {
                broadcastAt - registeredAt
            } else {
                null
            },
        )
        if (verdict.presence != believed) host.recordGeofencePresence(node.id, verdict.presence)
        if (verdict !is GeofenceVerdict.Discard) return true
        // "an" is always right here: only enter and exit are ever discarded, dwell
        // and away being waved through by the gate itself.
        host.report(
            node,
            "Ignored an $event at '${place.name}' — ${verdict.reason}",
            if (event in config.actedOnEvents) LogLevel.INFO else LogLevel.DEBUG,
        )
        return false
    }

    override fun activate(
        config: GeofenceConfig,
        node: WorkflowNode,
        host: TriggerHost,
    ): Flow<NodeOutput<GeofenceEvent>> {
        val place = resolvePlace(config, node, host) ?: return emptyFlow()
        val latitude = place.latitude
        val longitude = place.longitude
        val transitions = config.platformTransitions
        val emitted = config.emittedEvents
        return flow {
            val handle = host.armGeofence(
                nodeId = node.id,
                latitude = latitude,
                longitude = longitude,
                radiusMeters = place.radiusMeters,
                transitions = transitions,
                dwellDelayMs = config.dwellDelayMs,
                onResult = { result ->
                    when (result) {
                        // What the user asked for, not what the platform was
                        // told: arming "on away" registers enter and exit too,
                        // and announcing those would describe a macro nobody wrote.
                        is GeofenceArmResult.Registered -> host.report(
                            node,
                            "Watching '${place.name}' — ${place.radiusMeters.toInt()} m, " +
                                "on ${emitted.joinToString("/")}",
                        )

                        // The reason arrives already written for a person: a raw
                        // Play Services code names the developer, not the setting
                        // the user has to change.
                        is GeofenceArmResult.Refused -> host.report(
                            node,
                            "Not watching '${place.name}'. ${result.message}",
                            LogLevel.ERROR,
                        )
                    }
                },
            )
            try {
                // busEventsFor, not busEvents: a transition that arrived while
                // this process was still starting is waiting on the bus for this
                // node, and is handed over the moment the collector registers.
                host.busEventsFor(node.id)
                    .filter { it.source == TriggerSource.GEOFENCE && it.triggerNodeId == node.id }
                    .collect { bus ->
                        val event = bus.payload[KEY_EVENT].orEmpty()
                        // Ahead of the countdown as well as of the emit filter: an
                        // exit that never happened must not start an away period
                        // either, and there is nowhere later that could undo it.
                        if (!crossed(config, place, node, host, bus.payload, event)) return@collect
                        driveAwayCountdown(config, node, host, event)
                        if (event !in emitted) return@collect
                        if (bus.payload[TriggerBus.KEY_HELD] != null) {
                            host.report(node, "Arrived at '${place.name}' while the engine was starting; running now")
                        }
                        emit(
                            NodeOutput(
                                GeofenceEvent(
                                    triggerNodeId = node.id.value,
                                    transition = event,
                                    latitude = bus.payload[KEY_LATITUDE]?.toDoubleOrNull() ?: latitude,
                                    longitude = bus.payload[KEY_LONGITUDE]?.toDoubleOrNull() ?: longitude,
                                    accuracyMeters = bus.payload[KEY_ACCURACY]?.toFloatOrNull() ?: 0f,
                                    timestamp = bus.timestamp,
                                ),
                            ),
                        )
                    }
            } finally {
                // The fence goes; the away countdown deliberately does not. See
                // TriggerHost.armGeofenceAway — the exit that starts it also asks
                // the engine to re-arm, so tearing it down here would cancel it
                // moments after it was armed, every single time.
                handle.cancel()
            }
        }
    }

    companion object {
        val TYPE_ID = NodeTypeId("trigger.geofence")

        const val KEY_LATITUDE = "lat"
        const val KEY_LONGITUDE = "lng"
        const val KEY_ACCURACY = "accuracy"

        /**
         * When the receiver saw this transition, which is close enough to when the
         * platform sent it. Mirrors `GeofenceReceiver.KEY_TIMESTAMP`, which `engine`
         * may not import, exactly as [EVENT_AWAY] does.
         */
        const val KEY_TIMESTAMP = "timestamp"

        /**
         * The fourth `event` value, and the one with no [GeofenceTransition]
         * behind it: nothing in Play Services produces it, so there is no GMS
         * constant to map. Mirrored by `GeofenceReceiver.EVENT_AWAY`, which
         * `engine` may not import.
         */
        const val EVENT_AWAY = "away"
    }
}
