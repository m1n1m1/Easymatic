package com.example.ottomatic.feature.geofence

import androidx.compose.ui.res.stringResource
import com.example.ottomatic.R
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.ottomatic.domain.model.GeofencePlace
import com.example.ottomatic.feature.grapheditor.EditorColors

private val ROW_SHAPE = RoundedCornerShape(16.dp)

/**
 * The place library as a list, shared by the standalone Geofences screen and
 * the picker a geofence trigger opens.
 *
 * [onSelect] is what tapping a row does — pick it, in the picker; edit it, on
 * the standalone screen — while the pencil always edits. [selectedId] marks the
 * place a trigger currently references.
 */
@Composable
fun GeofencePlaceList(
    places: List<GeofencePlace>,
    onSelect: (GeofencePlace) -> Unit,
    onEdit: (GeofencePlace) -> Unit,
    onCreate: () -> Unit,
    modifier: Modifier = Modifier,
    selectedId: String? = null,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "new") { NewPlaceRow(onClick = onCreate) }
        if (places.isEmpty()) {
            item(key = "empty") {
                Text(
                    text = stringResource(R.string.geofence_no_places_yet_create_one),
                    style = MaterialTheme.typography.bodyMedium,
                    color = EditorColors.textSecondary,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 16.dp),
                )
            }
        }
        items(places, key = { it.id }) { place ->
            PlaceRow(
                place = place,
                selected = place.id == selectedId,
                onClick = { onSelect(place) },
                onEdit = { onEdit(place) },
            )
        }
    }
}

@Composable
private fun NewPlaceRow(onClick: () -> Unit) {
    val accent = EditorColors.triggerAccent
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ROW_SHAPE)
            .background(accent.copy(alpha = 0.08f))
            .border(1.dp, accent.copy(alpha = 0.35f), ROW_SHAPE)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.Filled.Add, contentDescription = null, tint = accent)
        Text(
            text = stringResource(R.string.geofence_new_place),
            style = MaterialTheme.typography.bodyLarge,
            color = accent,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun PlaceRow(
    place: GeofencePlace,
    selected: Boolean,
    onClick: () -> Unit,
    onEdit: () -> Unit,
) {
    val accent = EditorColors.triggerAccent
    val borderColor = if (selected) accent else EditorColors.nodeBorder
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ROW_SHAPE)
            .background(EditorColors.nodeBackground)
            .border(if (selected) 2.dp else 1.dp, borderColor, ROW_SHAPE)
            .clickable(onClick = onClick)
            .padding(start = 14.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(accent.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.LocationOn,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(20.dp),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = place.name,
                style = MaterialTheme.typography.bodyLarge,
                color = EditorColors.textPrimary,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = place.subtitle(),
                style = MaterialTheme.typography.bodySmall,
                color = EditorColors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onEdit) {
            Icon(
                imageVector = Icons.Filled.Edit,
                contentDescription = stringResource(R.string.geofence_edit_named, place.name),
                tint = EditorColors.textSecondary,
            )
        }
    }
}

/**
 * The second line of a place row: its address when one was resolved, otherwise
 * its coordinates — something identifiable either way. The radius is always
 * appended, because two places at the same address with different radii are
 * otherwise indistinguishable.
 */
@Composable
internal fun GeofencePlace.subtitle(): String {
    val where = address.ifBlank { stringResource(R.string.geofence_coordinates, latitude, longitude) }
    return stringResource(R.string.geofence_place_subtitle, where, formatRadius(radiusMeters))
}

/** Radius in the largest unit that stays readable: `250 m`, `1.4 km`. */
@Composable
internal fun formatRadius(meters: Float): String = if (meters >= METERS_PER_KM) {
    stringResource(R.string.geofence_radius_km, meters / METERS_PER_KM)
} else {
    stringResource(R.string.geofence_radius_m, meters.toInt())
}

private const val METERS_PER_KM = 1000f
