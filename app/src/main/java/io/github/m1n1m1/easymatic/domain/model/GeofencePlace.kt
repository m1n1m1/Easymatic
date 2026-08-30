package io.github.m1n1m1.easymatic.domain.model

import kotlinx.serialization.Serializable
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A named circular place, stored once and referenced by id from any number of
 * geofence triggers.
 *
 * Geofences are a *library* rather than per-node coordinates: the same "Home"
 * is usually wired into several macros, and moving house should be one edit,
 * not one per trigger. The radius belongs to the place because it describes the
 * place's extent; which transitions to react to (enter / exit / dwell) belongs
 * to the trigger, because two macros can watch the same place for opposite
 * events.
 *
 * [address] is the last label resolved by the editor's geocoder. It is purely
 * a display subtitle — nothing reads it back — so a failed or skipped geocode
 * leaves it blank rather than blocking a save.
 */
@Serializable
data class GeofencePlace(
    val id: String,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Float = DEFAULT_PLACE_RADIUS_METERS,
    val address: String = "",
) {
    /**
     * Metres between this place's centre and ([lat], [lng]).
     *
     * Lives on the place rather than beside its one caller for the reason
     * [TimeOfDay] is shared by the schedule trigger and its picker: there is one
     * reading of "how far away is this", and two would eventually disagree. The
     * parameters are named for the `lat`/`lng` payload keys a geofence
     * transition arrives with, which is also what keeps them from shadowing
     * [latitude] and [longitude].
     *
     * A haversine over a spherical earth, good to about half a percent — far
     * finer than any distinction a geofence draws, where the radius is tens or
     * hundreds of metres and the fix being measured carries its own uncertainty
     * in the same units. `android.location.Location.distanceBetween` is more
     * precise and would need a device to test; this stays a pure function with
     * JVM tests, which is the trade `WebUrl` already made.
     */
    fun distanceTo(lat: Double, lng: Double): Double {
        val halfDeltaLat = (lat - latitude).toRadians() / 2
        val halfDeltaLng = (lng - longitude).toRadians() / 2
        val a = sin(halfDeltaLat) * sin(halfDeltaLat) +
            cos(latitude.toRadians()) * cos(lat.toRadians()) * sin(halfDeltaLng) * sin(halfDeltaLng)
        // min() guards the antipodal case, where rounding can push `a` a hair
        // over 1 and asin() would answer NaN.
        return 2 * EARTH_RADIUS_METERS * asin(min(1.0, sqrt(a)))
    }

    companion object {
        /**
         * Platform geofences below roughly this radius are unreliable: the OS
         * trades accuracy for battery, so a small circle is missed as often as
         * it is hit. Used as the default, as the editor's "recommended
         * minimum" mark, and as `GeofenceGate`'s floor for how imprecise a
         * location fix may be and still be worth believing.
         */
        const val RELIABLE_MIN_RADIUS_METERS = 100f
    }
}

private const val DEFAULT_PLACE_RADIUS_METERS = 100f

/** Mean earth radius, the figure IUGG publishes for exactly this use. */
private const val EARTH_RADIUS_METERS = 6_371_008.8

private const val DEGREES_PER_HALF_TURN = 180.0

private fun Double.toRadians(): Double = this * PI / DEGREES_PER_HALF_TURN
