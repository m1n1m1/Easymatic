package com.example.ottomatic.domain.model

import kotlinx.serialization.Serializable

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
    companion object {
        /**
         * Platform geofences below roughly this radius are unreliable: the OS
         * trades accuracy for battery, so a small circle is missed as often as
         * it is hit. Used as the default and as the editor's "recommended
         * minimum" mark.
         */
        const val RELIABLE_MIN_RADIUS_METERS = 100f
    }
}

private const val DEFAULT_PLACE_RADIUS_METERS = 100f
