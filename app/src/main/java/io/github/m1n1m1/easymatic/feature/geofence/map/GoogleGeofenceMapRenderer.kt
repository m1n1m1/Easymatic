package io.github.m1n1m1.easymatic.feature.geofence.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import io.github.m1n1m1.easymatic.feature.grapheditor.EditorColors
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.Circle
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapType
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.rememberCameraPositionState
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop

/**
 * [GeofenceMapRenderer] backed by the Google Maps SDK (`maps-compose`) — the
 * same map MacroDroid's geofence editor uses. The only file in the app that
 * imports a map SDK.
 *
 * The fence centre is wherever the camera is pointing rather than a draggable
 * pin: a pin is a small target under a fingertip and it disappears under the
 * finger while being moved, whereas panning the whole map keeps the
 * destination visible the entire time. The crosshair marker is drawn by the
 * screen on top, not here, so it stays perfectly centred regardless of what
 * the map is doing.
 *
 * Needs `com.google.android.geo.API_KEY` in the manifest (injected from
 * `local.properties` at build time). Without it the SDK logs an authorisation
 * failure and renders empty tiles; every other control on the screen still
 * works, which is why this is not a hard failure.
 */
object GoogleGeofenceMapRenderer : GeofenceMapRenderer {

    @OptIn(FlowPreview::class) // debounce; stable in practice since 1.0.
    @Composable
    override fun Render(
        center: MapPoint,
        radiusMeters: Float,
        onCenterChange: (MapPoint) -> Unit,
        recenterTo: MapPoint?,
        showMyLocation: Boolean,
        modifier: Modifier,
    ) {
        val cameraPositionState = rememberCameraPositionState {
            position = CameraPosition.fromLatLngZoom(center.toLatLng(), zoomForRadius(radiusMeters))
        }
        val currentOnCenterChange by rememberUpdatedState(onCenterChange)

        // Report the centre back as the user pans. Debounced and dropping the
        // first value so settling the initial camera does not immediately mark
        // an untouched place as edited.
        LaunchedEffect(cameraPositionState) {
            snapshotFlow { cameraPositionState.position.target }
                .drop(1)
                .distinctUntilChanged()
                .debounce(CENTER_SETTLE_MS)
                .collect { currentOnCenterChange(MapPoint(it.latitude, it.longitude)) }
        }

        // A jump requested by the caller ("my location", a search result).
        LaunchedEffect(recenterTo) {
            val target = recenterTo ?: return@LaunchedEffect
            cameraPositionState.animate(
                CameraUpdateFactory.newLatLngZoom(target.toLatLng(), zoomForRadius(radiusMeters)),
            )
        }

        GoogleMap(
            modifier = modifier,
            cameraPositionState = cameraPositionState,
            properties = MapProperties(
                mapType = MapType.NORMAL,
                isMyLocationEnabled = showMyLocation,
            ),
            uiSettings = MapUiSettings(
                // The screen supplies its own "my location" control, and the
                // toolbar's Maps-app shortcuts are noise inside an editor.
                myLocationButtonEnabled = false,
                mapToolbarEnabled = false,
                zoomControlsEnabled = false,
                tiltGesturesEnabled = false,
                rotationGesturesEnabled = false,
            ),
        ) {
            Circle(
                center = cameraPositionState.position.target,
                radius = radiusMeters.toDouble(),
                strokeWidth = CIRCLE_STROKE_WIDTH,
                strokeColor = EditorColors.triggerAccent,
                fillColor = EditorColors.triggerAccent.copy(alpha = CIRCLE_FILL_ALPHA),
            )
        }
    }

    /**
     * A zoom level that frames a fence of [radiusMeters] with some margin.
     * Google's scale is logarithmic — one level per halving of ground distance
     * — so the radius maps onto it with a log2, then clamps to sane bounds.
     */
    private fun zoomForRadius(radiusMeters: Float): Float {
        val diameterWithMargin = (radiusMeters * 2 * FRAMING_MARGIN).coerceAtLeast(1f)
        val zoom = ZOOM_REFERENCE - log2(diameterWithMargin / REFERENCE_SPAN_METERS)
        return zoom.coerceIn(MIN_ZOOM, MAX_ZOOM)
    }

    private fun log2(value: Float): Float = (kotlin.math.ln(value.toDouble()) / kotlin.math.ln(2.0)).toFloat()

    private fun MapPoint.toLatLng() = LatLng(latitude, longitude)

    /** Zoom 16 shows roughly this much ground across a phone screen. */
    private const val REFERENCE_SPAN_METERS = 600f
    private const val ZOOM_REFERENCE = 16f
    private const val FRAMING_MARGIN = 2.2f
    private const val MIN_ZOOM = 9f
    private const val MAX_ZOOM = 18f

    /** Pans emit continuously; only the settled position is worth reporting. */
    private const val CENTER_SETTLE_MS = 150L

    private const val CIRCLE_STROKE_WIDTH = 4f
    private const val CIRCLE_FILL_ALPHA = 0.18f
}
