package io.github.m1n1m1.easymatic.engine.trigger

import io.github.m1n1m1.easymatic.domain.model.GeofencePlace
import kotlin.math.max

/**
 * Where a geofence trigger last believed the device was, relative to its own fence.
 *
 * [UNKNOWN] is a real state rather than a placeholder: it is what a node answers
 * before it has ever seen a transition, and it is the one state in which
 * [GeofenceGate] believes whatever the platform says.
 */
enum class GeofencePresence { UNKNOWN, INSIDE, OUTSIDE }

/** What [GeofenceGate] decided about one arriving transition. */
sealed interface GeofenceVerdict {

    /** Where the node should believe it is once this transition has been dealt with. */
    val presence: GeofencePresence

    /** A real crossing. The trigger fires, subject to its own event filter. */
    data class Accept(override val presence: GeofencePresence) : GeofenceVerdict

    /**
     * Not a crossing. [reason] is a clause that finishes the sentence
     * "Ignored an exit at 'Work' — …", so it reads for somebody holding the phone
     * rather than for whoever wrote the fence.
     */
    data class Discard(val reason: String, override val presence: GeofencePresence) : GeofenceVerdict
}

/**
 * Decides whether a geofence transition is a crossing or noise.
 *
 * **Why this exists.** `armGeofence` removes and re-adds its fence on every arm,
 * and a full re-arm happens on every process start, on boot, on every sticky
 * service restart and after every transition. Each re-registration resets the
 * fence's state inside Play Services, which then re-evaluates against the current
 * location and announces the result.
 * `setInitialTrigger(INITIAL_TRIGGER_ENTER)` suppresses only the *initial enter*;
 * a verdict of "outside" arrives as an ordinary `GEOFENCE_TRANSITION_EXIT`,
 * indistinguishable from a real departure. Before this gate, `trigger.geofence`
 * decided whether to fire with one set-membership test and had no idea whether the
 * device had ever been inside, so it fired.
 *
 * That is also why the symptom was **exits and not enters**: the phone spends the
 * night outside the fence it watches, so a state-reset artefact can only ever be an
 * exit. An enter artefact needs a re-arm while you are already inside, which is
 * rarer and — when it happens — looks like a plausible arrival.
 *
 * **Four guards, and the order matters.** Remembering where we were is the one that
 * fixes the re-registration artefact, because there the fix is often perfectly
 * accurate and the device really is outside. The accuracy guard covers the other
 * half: a phone stationary in doze with Wi-Fi scanning throttled falls back to a
 * cell-tower fix hundreds or thousands of metres wide, which lands outside a small
 * circle almost every time and inside it almost never. The two departure guards
 * ([SETTLING_MS] and the refusal to leave a place we never saw anyone arrive at) are
 * asymmetric on purpose — see [departureRefusal].
 *
 * Pure, so it is JVM-tested; the belief itself is persisted by the host.
 */
object GeofenceGate {

    /**
     * How long after a registration an uncorroborated exit is read as the
     * registration talking rather than as a departure.
     *
     * The same order of magnitude as `GEOFENCE_RESPONSIVENESS_MS`, which is how long
     * Play Services may sit on a real crossing before reporting it — a window
     * shorter than that would be a window a genuine exit routinely falls outside of
     * anyway.
     */
    const val SETTLING_MS = 120_000L

    /**
     * What to do about [event], given what this node [believed] and what the fix
     * that produced it looked like.
     *
     * [accuracyMeters] and [distanceMeters] are null when the transition carried no
     * location — which the away alarm never does — and a null must never be read as
     * a perfect fix, so each guard sits out rather than failing closed.
     *
     * [sinceRegisteredMs] is how long after this fence was last registered the
     * transition was broadcast, or null when nothing knows.
     */
    // One early return per rule, and folding them into a chain is what would make the
    // order of the rules stop being visible. Every parameter is one piece of evidence
    // about a single transition; bundling them into a holder would only move the list.
    @Suppress("ReturnCount", "LongParameterList")
    fun judge(
        event: String,
        believed: GeofencePresence,
        accuracyMeters: Float?,
        distanceMeters: Double?,
        radiusMeters: Float,
        sinceRegisteredMs: Long?,
    ): GeofenceVerdict {
        val reported = when (event) {
            GeofenceTransition.ENTER.payloadValue, GeofenceTransition.DWELL.payloadValue ->
                GeofencePresence.INSIDE

            GeofenceTransition.EXIT.payloadValue -> GeofencePresence.OUTSIDE

            // "away" is our own alarm rather than a platform transition, so there is
            // nothing here to judge and nothing it can teach us about where we are.
            // Anything unrecognised takes the same road, which fails open.
            else -> return GeofenceVerdict.Accept(believed)
        }

        // A dwell is a fix that stayed put for the whole loitering delay, and it
        // legitimately arrives while we already believe we are inside — that is what
        // dwelling *is*. Every guard below would be wrong about it.
        if (event == GeofenceTransition.DWELL.payloadValue) return GeofenceVerdict.Accept(reported)

        untrustworthy(accuracyMeters, radiusMeters)?.let {
            // Deliberately keeps `believed`: a fix we would not act on is not one we
            // should learn from either, or the next good fix is judged against noise.
            return GeofenceVerdict.Discard(it, believed)
        }

        contradiction(reported, accuracyMeters, distanceMeters, radiusMeters)?.let {
            return GeofenceVerdict.Discard(it, believed)
        }

        if (reported == GeofencePresence.OUTSIDE) {
            departureRefusal(believed, distanceMeters, radiusMeters, sinceRegisteredMs)?.let { return it }
        }

        if (believed == reported) {
            return GeofenceVerdict.Discard(
                if (reported == GeofencePresence.INSIDE) {
                    "the phone was already inside"
                } else {
                    "the phone was already outside"
                },
                reported,
            )
        }

        // A genuine crossing, or the first arrival this node has ever seen.
        //
        // An arrival from UNKNOWN is failing open on purpose, and it is the half of
        // that choice worth keeping: refusing an enter costs *every* departure after
        // it, because departureRefusal only lets one through on the strength of a
        // remembered arrival. Refusing an exit costs one trip.
        return GeofenceVerdict.Accept(reported)
    }

    /**
     * Why this exit is not a departure, or null when it is one.
     *
     * Both rules here apply to exits and to nothing else, and that asymmetry is the
     * design rather than an oversight: the phone sleeps outside the fence it
     * watches, so a state-reset artefact can only ever be an exit, and the two
     * mistakes cost wildly different amounts. A refused enter costs every departure
     * after it — the second rule below will not believe one without an arrival
     * behind it — where a refused exit costs one trip.
     *
     * **The settling window** is the one that survives a re-registration. Since
     * `armGeofence` no longer re-adds an unchanged fence, a registration is a rare,
     * deliberate event: the first arm, a moved place, a reboot, or the daily safety
     * re-register. Play Services answers each one by re-deriving the fence's state
     * and announcing it, and "outside" is announced as an ordinary EXIT. What tells
     * that apart from walking out is *when* it arrives — seconds after a
     * registration, rather than the minutes-to-hours a real departure sits at — and
     * it is only refused when the fix does not positively put the phone beyond the
     * radius, so a genuine departure moments after arming still fires. The
     * transition's own broadcast time is what is measured, not "now": the receiver
     * stamps it before it asks the engine to re-arm, so the comparison cannot be
     * poisoned by the re-arm the transition itself causes.
     *
     * **The remembered arrival** is the rule that only became affordable once every
     * fence started watching both directions. Before that, refusing an exit from
     * UNKNOWN would have stranded a node that was never told about arrivals at all.
     * Now `setInitialTrigger(INITIAL_TRIGGER_ENTER)` tells a node armed inside its
     * own place so at once, and a node armed outside has to come back before it can
     * leave — so the only thing this can swallow is a departure from a place nobody
     * was ever seen arriving at, which is the artefact's own signature.
     *
     * It records OUTSIDE rather than keeping the belief, which is the opposite of
     * what the accuracy and contradiction guards do, and deliberately: those two
     * doubt the *fix*, and a fix worth doubting must not teach. This one believes
     * the fix completely — it has already passed both — and doubts only that a
     * departure means anything with no arrival behind it. So the next exit is judged
     * from a true starting point instead of meeting UNKNOWN a second time.
     */
    // Two rules and a fall-through, kept as three returns for judge's own reason.
    @Suppress("ReturnCount")
    private fun departureRefusal(
        believed: GeofencePresence,
        distanceMeters: Double?,
        radiusMeters: Float,
        sinceRegisteredMs: Long?,
    ): GeofenceVerdict.Discard? {
        val corroborated = distanceMeters != null && distanceMeters > radiusMeters
        if (!corroborated && sinceRegisteredMs != null && sinceRegisteredMs in 0..SETTLING_MS) {
            return GeofenceVerdict.Discard(
                "it arrived ${sinceRegisteredMs / MILLIS_PER_SECOND} s after this fence was registered, " +
                    "which is the registration saying where the phone is rather than a departure",
                believed,
            )
        }
        if (believed == GeofencePresence.UNKNOWN) {
            return GeofenceVerdict.Discard(
                "nothing has ever seen the phone arrive here, and leaving needs an arrival behind it",
                GeofencePresence.OUTSIDE,
            )
        }
        return null
    }

    /**
     * Why this fix cannot tell inside from outside, or null when it can.
     *
     * The floor is [GeofencePlace.RELIABLE_MIN_RADIUS_METERS] rather than a second
     * constant of its own: it already names the smallest circle worth trusting a
     * platform geofence with, which is the same quantity read from the other side.
     * Without it a 50 m place — which the editor does allow — would reject the
     * ordinary 40 m Wi-Fi fix that is the best anyone ever gets indoors.
     */
    private fun untrustworthy(accuracyMeters: Float?, radiusMeters: Float): String? {
        val worstUsable = max(radiusMeters, GeofencePlace.RELIABLE_MIN_RADIUS_METERS)
        // A null accuracy is a transition that carried no location at all, and a
        // zero is the same claim dressed as a number — neither is a perfect fix,
        // so neither is judged here.
        if (accuracyMeters == null || accuracyMeters <= 0f || accuracyMeters <= worstUsable) return null
        return "the location fix was only accurate to ${accuracyMeters.toInt()} m, which says nothing " +
            "about a ${radiusMeters.toInt()} m circle"
    }

    /**
     * Why the fix disagrees with the transition it came attached to, or null.
     *
     * This is what keeps the remembered presence from wedging. Suppressing repeats
     * means trusting a belief, and a belief that drifts out of step with the world —
     * because an enter was genuinely missed while the phone had no signal — would
     * swallow every departure after it. A fix whose whole uncertainty circle falls
     * on one side of the fence is stronger evidence than anything remembered, so a
     * transition that contradicts it is dropped rather than allowed to teach.
     */
    private fun contradiction(
        reported: GeofencePresence,
        accuracyMeters: Float?,
        distanceMeters: Double?,
        radiusMeters: Float,
    ): String? {
        if (distanceMeters == null) return null
        val slack = (accuracyMeters ?: 0f).toDouble()
        val radius = radiusMeters.toDouble()
        val wholly = when {
            reported == GeofencePresence.OUTSIDE && distanceMeters + slack < radius -> "inside"
            reported == GeofencePresence.INSIDE && distanceMeters - slack > radius -> "outside"
            // Straddling the boundary, which is what crossing it looks like.
            else -> null
        }
        return wholly?.let {
            "the fix put the phone ${distanceMeters.toInt()} m from the centre of a " +
                "${radiusMeters.toInt()} m circle, which is $it it"
        }
    }
}

private const val MILLIS_PER_SECOND = 1_000L
