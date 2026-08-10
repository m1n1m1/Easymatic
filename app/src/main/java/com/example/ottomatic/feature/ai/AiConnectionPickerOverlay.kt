package com.example.ottomatic.feature.ai

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
 * Picks the connection an Ask AI node sends through, or adds a new one.
 *
 * `MailAccountPickerOverlay`'s twin: the deferred-pick idiom every chooser in the
 * app uses — the choice is held until the exit animation has played — with the
 * editor stacked above it and the "created here, so selected on save" behaviour.
 *
 * That last part matters more here than anywhere except the mail one, and for a
 * sharper reason: for almost everybody the first connection they ever add will be
 * added from inside this overlay, having dropped an Ask AI node and found the
 * Connection field empty — and unlike a mail account, they will very likely be
 * making an API key for the first time in their life while they do it.
 */
@Composable
fun AiConnectionPickerOverlay(
    viewModel: AiConnectionsViewModel,
    selectedId: String?,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val state by viewModel.uiState.collectAsState()
    var picked by remember { mutableStateOf<String?>(null) }

    EditorOverlay(
        title = "Choose a connection",
        onClose = { picked?.let(onPick) ?: onDismiss() },
    ) { dismiss ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding(),
        ) {
            AiConnectionList(
                connections = state.connections,
                selectedId = selectedId,
                needsKey = viewModel::needsKey,
                onSelect = { connection ->
                    picked = connection.id
                    dismiss()
                },
                onEdit = { connection -> viewModel.editConnection(connection.id) },
                onAdd = viewModel::addConnection,
            )
        }
    }

    state.draft?.let { draft ->
        AiConnectionEditorOverlay(
            draft = draft,
            viewModel = viewModel,
            onClose = viewModel::closeEditor,
            onSaved = { savedId -> if (draft.isNew) onPick(savedId) },
        )
    }
}
