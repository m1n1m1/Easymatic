package com.example.ottomatic.feature.nfc

import androidx.compose.ui.res.stringResource
import com.example.ottomatic.R
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ottomatic.feature.grapheditor.EditorColors

/**
 * The standalone tag library, reached from the workflow list.
 *
 * Same argument as the Geofences screen: a library shared across macros needs
 * somewhere to be audited and pruned that is not inside one node's config form.
 * You rename a tag once when the sticker moves from the desk to the car, not once
 * per trigger watching it — and a tag you have stopped using is invisible from
 * every picker that is not currently open.
 */
@Composable
fun NfcTagsScreen(
    viewModel: NfcTagsViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.uiState.collectAsState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(EditorColors.canvasBackground),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Surface(color = EditorColors.chrome) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .height(60.dp)
                        .padding(start = 6.dp, end = 18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.nfc_back),
                            tint = EditorColors.textPrimary,
                        )
                    }
                    Text(
                        text = stringResource(R.string.nfc_nfc_tags),
                        color = EditorColors.textPrimary,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
            }

            NfcTagList(
                tags = state.tags,
                // Nothing to pick here, so tapping a row and tapping the pencil
                // are the same act.
                onSelect = { tag -> viewModel.editTag(tag.uid) },
                onEdit = { tag -> viewModel.editTag(tag.uid) },
                onScan = viewModel::scanNew,
                modifier = Modifier.navigationBarsPadding(),
            )
        }
    }

    state.draft?.let { draft ->
        NfcTagEditorOverlay(
            draft = draft,
            viewModel = viewModel,
            onClose = viewModel::closeEditor,
        )
    }

    // Stacked above the tag's own overlay, so closing it returns to the tag rather
    // than all the way out to the list.
    state.write?.let { write ->
        NfcWriteOverlay(draft = write, viewModel = viewModel, onClose = viewModel::closeWrite)
    }
}
