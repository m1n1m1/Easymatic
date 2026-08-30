package io.github.m1n1m1.easymatic.feature.geofence

import androidx.compose.ui.res.stringResource
import io.github.m1n1m1.easymatic.R
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import io.github.m1n1m1.easymatic.feature.grapheditor.EditorOverlay

/**
 * Picks a place for a geofence trigger's `@Picker` config field.
 *
 * Editing is reachable from here rather than only from the standalone screen:
 * the moment you notice a fence is in the wrong spot is while wiring a trigger
 * to it, and sending the user out to a different screen to fix it would lose
 * the node they were configuring.
 *
 * Follows the node palette's deferred-pick idiom — the choice is held until the
 * exit animation has played — so picking a place feels the same as picking a
 * node.
 */
@Composable
fun GeofencePlacePickerOverlay(
    viewModel: GeofencePlacesViewModel,
    selectedId: String?,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val state by viewModel.uiState.collectAsState()
    var picked by remember { mutableStateOf<String?>(null) }

    EditorOverlay(
        title = stringResource(R.string.geofence_choose_a_place),
        onClose = { picked?.let(onPick) ?: onDismiss() },
    ) { dismiss ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding(),
        ) {
            GeofencePlaceList(
                places = state.places,
                selectedId = selectedId,
                onSelect = { place ->
                    picked = place.id
                    dismiss()
                },
                onEdit = { place -> viewModel.editPlace(place.id) },
                onCreate = viewModel::createPlace,
            )
        }
    }

    // Stacked above the picker. A place created from here is selected on save:
    // the user opened the picker to choose one, and having just drawn it on a
    // map they should not have to find it in the list afterwards.
    state.draft?.let { draft ->
        GeofencePlaceEditorOverlay(
            draft = draft,
            viewModel = viewModel,
            onClose = viewModel::closeEditor,
            onSaved = { savedId -> if (draft.isNew) onPick(savedId) },
        )
    }
}
