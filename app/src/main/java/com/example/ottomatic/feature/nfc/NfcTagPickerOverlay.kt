package com.example.ottomatic.feature.nfc

import androidx.compose.ui.res.stringResource
import com.example.ottomatic.R
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
import com.example.ottomatic.feature.grapheditor.EditorOverlay

/**
 * Picks the tag a `trigger.nfc` watches, or scans a new one.
 *
 * Follows the deferred-pick idiom every chooser in the app uses — the choice is
 * held until the exit animation has played — and stacks the capture overlay above
 * itself exactly as `GeofencePlacePickerOverlay` stacks its map editor, including
 * the "created here, so selected on save" behaviour: somebody who opened this to
 * choose a tag and has just held one to their phone should not then have to find
 * it in the list.
 */
@Composable
fun NfcTagPickerOverlay(
    viewModel: NfcTagsViewModel,
    selectedUid: String?,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val state by viewModel.uiState.collectAsState()
    var picked by remember { mutableStateOf<String?>(null) }

    EditorOverlay(
        title = stringResource(R.string.nfc_choose_a_tag),
        onClose = { picked?.let(onPick) ?: onDismiss() },
    ) { dismiss ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding(),
        ) {
            NfcTagList(
                tags = state.tags,
                selectedUid = selectedUid,
                showAnyTag = true,
                onPickAny = {
                    picked = ""
                    dismiss()
                },
                onSelect = { tag ->
                    picked = tag.uid
                    dismiss()
                },
                onEdit = { tag -> viewModel.editTag(tag.uid) },
                onScan = viewModel::scanNew,
            )
        }
    }

    state.draft?.let { draft ->
        NfcTagEditorOverlay(
            draft = draft,
            viewModel = viewModel,
            onClose = viewModel::closeEditor,
            onSaved = { savedUid -> if (draft.isNew) onPick(savedUid) },
        )
    }
}
