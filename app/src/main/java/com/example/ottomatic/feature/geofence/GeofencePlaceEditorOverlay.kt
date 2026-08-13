package com.example.ottomatic.feature.geofence

import androidx.compose.ui.res.stringResource
import com.example.ottomatic.R
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.ottomatic.core.permissions.Permissions
import com.example.ottomatic.data.location.PlaceSuggestion
import com.example.ottomatic.domain.model.GeofencePlace
import com.example.ottomatic.feature.geofence.map.geofenceMapRenderer
import com.example.ottomatic.feature.grapheditor.EditorColors
import com.example.ottomatic.feature.grapheditor.EditorOverlay
import com.example.ottomatic.feature.permissions.rememberPermissionState
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * The map editor for a single geofence place — the MacroDroid-style page this
 * whole feature exists for.
 *
 * The map fills the screen with the controls floating over it, rather than the
 * map being one field in a form: placing a fence is a spatial task, and a map
 * squeezed into a 200dp card is not one you can aim with.
 */
@Composable
@Suppress("LongMethod") // One declarative screen: map + overlaid controls + bottom sheet of fields.
fun GeofencePlaceEditorOverlay(
    draft: GeofenceDraft,
    viewModel: GeofencePlacesViewModel,
    onClose: () -> Unit,
    onSaved: (String) -> Unit = {},
) {
    val state by viewModel.uiState.collectAsState()
    val locationPermission = rememberPermissionState(listOf(Permissions.ACCESS_FINE_LOCATION))

    EditorOverlay(
        title = if (draft.isNew)
            stringResource(R.string.geofence_new_place_2) else stringResource(R.string.geofence_edit_place),
        onClose = onClose,
        action = {
            if (!draft.isNew) {
                IconButton(onClick = { viewModel.delete(requireNotNull(draft.id)) }) {
                    Icon(Icons.Filled.Delete, contentDescription =
                        stringResource(R.string.geofence_delete_place), tint = EditorColors.textSecondary)
                }
            }
            TextButton(
                onClick = { viewModel.save(onSaved) },
                enabled = draft.canSave,
            ) {
                Text(stringResource(R.string.geofence_save))
            }
        },
    ) { _ ->
        Column(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                geofenceMapRenderer.Render(
                    center = draft.center,
                    radiusMeters = draft.radiusMeters,
                    onCenterChange = viewModel::updateCenter,
                    recenterTo = draft.recenterTo,
                    showMyLocation = locationPermission.allGranted,
                    modifier = Modifier.fillMaxSize(),
                )
                // Drawn here rather than by the map: the fence centre is
                // wherever the camera points, so the crosshair must be pinned
                // to the viewport, not to a coordinate.
                CenterCrosshair(modifier = Modifier.align(Alignment.Center))

                SearchOverlay(
                    query = state.searchQuery,
                    suggestions = state.suggestions,
                    onQueryChange = viewModel::updateSearchQuery,
                    onPick = viewModel::applySuggestion,
                    modifier = Modifier.align(Alignment.TopCenter),
                )

                MyLocationButton(
                    locating = state.locating,
                    onClick = {
                        if (locationPermission.allGranted) {
                            viewModel.useCurrentLocation()
                        } else {
                            locationPermission.request()
                        }
                    },
                    modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                )
            }

            EditorFields(
                draft = draft,
                onNameChange = viewModel::updateName,
                onRadiusChange = viewModel::updateRadius,
            )
        }
    }
}

/** Name field, radius slider and the coordinate readout, below the map. */
@Composable
private fun EditorFields(
    draft: GeofenceDraft,
    onNameChange: (String) -> Unit,
    onRadiusChange: (Float) -> Unit,
) {
    Surface(color = EditorColors.chrome) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedTextField(
                value = draft.name,
                onValueChange = onNameChange,
                label = { Text(stringResource(R.string.geofence_name)) },
                placeholder = { Text(stringResource(R.string.geofence_home_work_gym)) },
                singleLine = true,
                colors = darkFieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.geofence_radius),
                    style = MaterialTheme.typography.bodyMedium,
                    color = EditorColors.textSecondary,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = formatRadius(draft.radiusMeters),
                    style = MaterialTheme.typography.bodyMedium,
                    color = EditorColors.textPrimary,
                    fontWeight = FontWeight.Medium,
                )
            }
            Slider(
                value = radiusToSlider(draft.radiusMeters),
                onValueChange = { onRadiusChange(sliderToRadius(it)) },
                colors = SliderDefaults.colors(
                    thumbColor = EditorColors.triggerAccent,
                    activeTrackColor = EditorColors.triggerAccent,
                    inactiveTrackColor = EditorColors.nodeBorder,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            if (draft.radiusMeters < GeofencePlace.RELIABLE_MIN_RADIUS_METERS) {
                Text(
                    text = stringResource(
                        R.string.geofence_small_radius_warning,
                        GeofencePlace.RELIABLE_MIN_RADIUS_METERS.toInt(),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = EditorColors.triggerAccent,
                )
            }
            Text(
                text = draft.address.ifBlank {
                    "%.5f, %.5f".format(draft.center.latitude, draft.center.longitude)
                },
                style = MaterialTheme.typography.bodySmall,
                color = EditorColors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** The fixed centre mark the map pans underneath. */
@Composable
private fun CenterCrosshair(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(14.dp)
            .clip(RoundedCornerShape(50))
            .background(EditorColors.triggerAccent)
            .border(2.dp, EditorColors.textPrimary, RoundedCornerShape(50)),
    )
}

@Composable
private fun MyLocationButton(
    locating: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = EditorColors.chrome,
        shape = RoundedCornerShape(50),
        modifier = modifier.size(48.dp),
    ) {
        Box(
            modifier = Modifier.fillMaxSize().clickable(enabled = !locating, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            if (locating) {
                CircularProgressIndicator(
                    color = EditorColors.triggerAccent,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(20.dp),
                )
            } else {
                Icon(
                    imageVector = Icons.Filled.MyLocation,
                    contentDescription = stringResource(R.string.geofence_centre_on_my_location),
                    tint = EditorColors.textPrimary,
                )
            }
        }
    }
}

/** Address search bar floating over the top of the map, with its result list. */
@Composable
private fun SearchOverlay(
    query: String,
    suggestions: List<PlaceSuggestion>,
    onQueryChange: (String) -> Unit,
    onPick: (PlaceSuggestion) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.padding(12.dp)) {
        Surface(color = EditorColors.chrome, shape = RoundedCornerShape(14.dp)) {
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                placeholder = { Text(stringResource(R.string.geofence_search_an_address_or_place)) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { onQueryChange("") }) {
                            Icon(Icons.Filled.Close, contentDescription =
                                stringResource(R.string.geofence_clear_search))
                        }
                    }
                },
                singleLine = true,
                colors = darkFieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (suggestions.isNotEmpty()) {
            Surface(
                color = EditorColors.chrome,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.padding(top = 6.dp),
            ) {
                Column {
                    suggestions.forEach { suggestion ->
                        Text(
                            text = suggestion.label,
                            style = MaterialTheme.typography.bodyMedium,
                            color = EditorColors.textPrimary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPick(suggestion) }
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun darkFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = EditorColors.textPrimary,
    unfocusedTextColor = EditorColors.textPrimary,
    cursorColor = EditorColors.portSnap,
    focusedBorderColor = EditorColors.portSnap,
    unfocusedBorderColor = EditorColors.chromeBorder,
    focusedLabelColor = EditorColors.portSnap,
    unfocusedLabelColor = EditorColors.textSecondary,
    focusedLeadingIconColor = EditorColors.textSecondary,
    unfocusedLeadingIconColor = EditorColors.textSecondary,
    focusedTrailingIconColor = EditorColors.textSecondary,
    unfocusedTrailingIconColor = EditorColors.textSecondary,
    focusedPlaceholderColor = EditorColors.textSecondary,
    unfocusedPlaceholderColor = EditorColors.textSecondary,
)

/**
 * The radius slider is logarithmic. A linear 50 m–5 km track would spend 98% of
 * its length on radii nobody picks and make the useful 100–300 m range a
 * two-pixel sliver; on a log track every part of the range is equally
 * adjustable.
 */
private fun radiusToSlider(meters: Float): Float {
    val clamped = meters.coerceIn(MIN_RADIUS_METERS, MAX_RADIUS_METERS)
    return kotlin.math.ln(clamped / MIN_RADIUS_METERS) / kotlin.math.ln(MAX_RADIUS_METERS / MIN_RADIUS_METERS)
}

private fun sliderToRadius(position: Float): Float {
    val meters = MIN_RADIUS_METERS * (MAX_RADIUS_METERS / MIN_RADIUS_METERS).pow(position)
    // Snap to a step the readout can show without a decimal place jitter.
    val step = if (meters < STEP_SWITCH_METERS) SMALL_STEP_METERS else LARGE_STEP_METERS
    return ((meters / step).roundToInt() * step).coerceIn(MIN_RADIUS_METERS, MAX_RADIUS_METERS)
}

private const val MIN_RADIUS_METERS = 50f
private const val MAX_RADIUS_METERS = 5000f
private const val STEP_SWITCH_METERS = 500f
private const val SMALL_STEP_METERS = 10f
private const val LARGE_STEP_METERS = 50f
