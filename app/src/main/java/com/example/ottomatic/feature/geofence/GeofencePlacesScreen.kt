package com.example.ottomatic.feature.geofence

import androidx.compose.ui.res.stringResource
import com.example.ottomatic.R
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.ottomatic.feature.SettingsTopBar
import com.example.ottomatic.feature.grapheditor.EditorColors

/**
 * The standalone place library, reached from the workflow list.
 *
 * Places are worth managing outside any particular macro — you rename "Work"
 * once when you change jobs, not once per trigger that watches it — so the
 * library is a destination of its own, not only a picker hidden inside a node's
 * config.
 */
@Composable
fun GeofencePlacesScreen(
    viewModel: GeofencePlacesViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.uiState.collectAsState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(EditorColors.canvasBackground),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            SettingsTopBar(
                title = stringResource(R.string.geofence_geofences),
                contentDescription = stringResource(R.string.geofence_back),
                onBack = onBack,
            )

            GeofencePlaceList(
                places = state.places,
                // Tapping a row here means "open it" — there is nothing to
                // pick, so selecting and editing are the same act.
                onSelect = { place -> viewModel.editPlace(place.id) },
                onEdit = { place -> viewModel.editPlace(place.id) },
                onCreate = viewModel::createPlace,
                modifier = Modifier.navigationBarsPadding(),
            )
        }
    }

    state.draft?.let { draft ->
        GeofencePlaceEditorOverlay(
            draft = draft,
            viewModel = viewModel,
            onClose = viewModel::closeEditor,
        )
    }
}
