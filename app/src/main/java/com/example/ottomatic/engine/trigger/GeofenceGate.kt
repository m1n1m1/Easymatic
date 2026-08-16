package com.example.ottomatic.engine.trigger

import com.example.ottomatic.domain.model.GeofencePlace
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
 * **Two guards, and the order matters.** Remembering where we were is the one that
 * fixes the re-registration artefact, because there the fix is often perfectly
 * accurate and the device really is outside. The accuracy guard covers the other
 * half: a phone stationary in doze with Wi-Fi scanning throttled falls back to a
 * cell-tower fix hundreds or thousands of metres wide, which lands outside a small
 * circle almost every time and inside it almost never.
 *
 * Pure, so it is JVM-tested; the belief itself is persisted by the host.
 */
object GeofenceGate {

    /**
     * What to do about [event], given what this node [believed] and what the fix
     * that produced it looked like.
     *
     * [accuracyMeters] and [distanceMeters] are null when the transition carried no
     * location — which the away alarm never does — and a null must never be read as
     * a perfect fix, so each guard sits out rather than failing closed.
     */
    // One early return per rule, and folding them into a chain is what would make the
    // order of the rules stop being visible.
    @Suppress("ReturnCount")
    fun judge(
        event: String,
        believed: GeofencePresence,
        accuracyMeters: Float?,
        distanceMeters: Double?,
        radiusMeters: Float,
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
        // dwelling *is*. Both guards below would be wrong about it.
        if (event == GeofenceTransition.DWELL.payloadValue) return GeofenceVerdict.Accept(reported)

        untrustworthy(accuracyMeters, radiusMeters)?.let {
            // Deliberately keeps `believed`: a fix we would not act on is not one we
            // should learn from either, or the next good fix is judged against noise.
            return GeofenceVerdict.Discard(it, believed)
        }

        contradiction(reported, accuracyMeters, distanceMeters, radiusMeters)?.let {
            return GeofenceVerdict.Discard(it, believed)
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

        // A genuine crossing, or the first transition this node has ever seen.
        //
        // UNKNOWN accepting is failing open on purpose. The belief is persisted, so
        // UNKNOWN is a once-per-node state and the choice costs at most one event
        // ever — and swallowing somebody's first departure minutes after they built
        // the macro is the worse of the two ways to be wrong.
        return GeofenceVerdict.Accept(reported)
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
