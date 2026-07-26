package com.example.ottomatic.feature.geofence.map

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** A WGS84 coordinate, in the editor's own terms rather than any map SDK's. */
data class MapPoint(
    val latitude: Double,
    val longitude: Double,
)

/**
 * The map surface behind the geofence editor: a draggable view of the world
 * with the fence drawn on it.
 *
 * This exists so exactly one file imports a map SDK. The editor screen is a
 * fair amount of UI — name field, radius slider, search, permissions — and none
 * of it should have to change to swap Google Maps for osmdroid or anything
 * else; a replacement is a second implementation plus a one-line change to
 * [geofenceMapRenderer]. Note the interface deals only in [MapPoint] and
 * metres, so no SDK type escapes into the rest of `feature/`.
 */
interface GeofenceMapRenderer {

    /**
     * Draws the map centred on [center] with a [radiusMeters] circle around it,
     * calling [onCenterChange] as the user pans.
     *
     * [recenterTo] is a *command*, not the current centre: when it changes to a
     * non-null value the map animates there. This distinction matters because
     * the map is the authority on its own position while the user is dragging —
     * feeding [center] back in as the camera target every frame would fight the
     * gesture. "Use my location" and search results set [recenterTo] instead.
     *
     * [showMyLocation] draws the blue dot; the caller is responsible for only
     * passing true once location permission is actually granted.
     */
    @Composable
    fun Render(
        center: MapPoint,
        radiusMeters: Float,
        onCenterChange: (MapPoint) -> Unit,
        recenterTo: MapPoint?,
        showMyLocation: Boolean,
        modifier: Modifier,
    )
}

/**
 * The map implementation the app is built with. The single point of
 * substitution described on [GeofenceMapRenderer].
 */
val geofenceMapRenderer: GeofenceMapRenderer = GoogleGeofenceMapRenderer
