package io.github.m1n1m1.easymatic.feature.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.m1n1m1.easymatic.R
import io.github.m1n1m1.easymatic.domain.model.AiConnection
import io.github.m1n1m1.easymatic.domain.model.AiModelProfile
import io.github.m1n1m1.easymatic.feature.grapheditor.EditorColors
import io.github.m1n1m1.easymatic.feature.grapheditor.EditorOverlay

private val ROW_SHAPE = RoundedCornerShape(16.dp)

/**
 * Picks the saved way of asking an AI node sends through, or adds a new one.
 *
 * **The list is profiles grouped under their accounts**, which is the shape the whole
 * restructure is for: an account is a key and a provider, and what a macro actually
 * chooses is "my household assistant" or "the terse summariser". The account is a
 * heading rather than a row precisely because it is not the answer — a heading you
 * cannot pick is the clearest way to say that choosing a model already determines it.
 *
 * `MailAccountPickerOverlay`'s twin otherwise: the deferred-pick idiom every chooser
 * in the app uses — the choice is held until the exit animation has played — with the
 * editor stacked above it and the "created here, so selected on save" behaviour.
 *
 * That last part matters more here than anywhere except the mail one, and for a
 * sharper reason: for almost everybody the first connection they ever add will be
 * added from inside this overlay, having dropped an Ask AI node and found the Model
 * field empty — and unlike a mail account, they will very likely be making an API key
 * for the first time in their life while they do it.
 */
@Composable
fun AiModelPickerOverlay(
    viewModel: AiConnectionsViewModel,
    selectedId: String?,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val state by viewModel.uiState.collectAsState()
    var picked by remember { mutableStateOf<String?>(null) }

    EditorOverlay(
        title = stringResource(R.string.ai_choose_a_model),
        onClose = { picked?.let(onPick) ?: onDismiss() },
    ) { dismiss ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "add") { AddConnectionRow(onClick = viewModel::addConnection) }
            if (state.connections.isEmpty()) {
                item(key = "empty") {
                    Text(
                        text = stringResource(R.string.ai_no_connections_yet_add_one),
                        style = MaterialTheme.typography.bodyMedium,
                        color = EditorColors.textSecondary,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 16.dp),
                    )
                }
            }
            state.connections.forEach { connection ->
                item(key = "account-${connection.id}") {
                    AccountHeading(
                        connection = connection,
                        needsKey = viewModel.needsKey(connection.id),
                        onEdit = { viewModel.editConnection(connection.id) },
                    )
                }
                if (connection.models.isEmpty()) {
                    item(key = "none-${connection.id}") {
                        Text(
                            text = stringResource(R.string.ai_no_models_on_this_account),
                            style = MaterialTheme.typography.bodySmall,
                            color = EditorColors.textSecondary,
                            modifier = Modifier.padding(start = 16.dp, bottom = 4.dp),
                        )
                    }
                }
                items(connection.models, key = { "model-${it.id}" }) { profile ->
                    ModelPickerRow(
                        profile = profile,
                        selected = profile.id == selectedId,
                        onClick = {
                            picked = profile.id
                            dismiss()
                        },
                    )
                }
            }
        }
    }

    state.draft?.let { draft ->
        AiConnectionEditorOverlay(
            draft = draft,
            viewModel = viewModel,
            onClose = viewModel::closeEditor,
            // A connection created from here is saved with one model on it, so the node
            // can be pointed at something rather than sent back to an empty list. The
            // id is the profile's — see `AiConnectionsViewModel.save`.
            onSaved = { profileId -> if (draft.isNew && profileId.isNotBlank()) onPick(profileId) },
        )
    }
}

/**
 * One account, as a heading rather than something to choose.
 *
 * It carries the pencil and the "key needs pasting in again" badge, because both are
 * facts about the *account* — a revoked key stops every profile under it at once, and
 * showing that once above the group rather than on each row is what makes the grouping
 * worth having.
 */
@Composable
private fun AccountHeading(
    connection: AiConnection,
    needsKey: Boolean,
    onEdit: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, start = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = connection.name,
                style = MaterialTheme.typography.labelLarge,
                color = EditorColors.textPrimary,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (needsKey) {
                    Icon(
                        imageVector = Icons.Filled.ErrorOutline,
                        contentDescription = null,
                        tint = EditorColors.warnAccent,
                        modifier = Modifier.size(14.dp),
                    )
                }
                Text(
                    text = if (needsKey) {
                        stringResource(R.string.ai_key_needs_pasting_in_again)
                    } else {
                        stringResource(connection.provider.labelRes())
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (needsKey) EditorColors.warnAccent else EditorColors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        IconButton(onClick = onEdit) {
            Icon(
                imageVector = Icons.Filled.Edit,
                contentDescription = stringResource(R.string.ai_edit),
                tint = EditorColors.textSecondary,
            )
        }
    }
}

@Composable
private fun ModelPickerRow(
    profile: AiModelProfile,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ROW_SHAPE)
            .background(EditorColors.nodeBackground)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) EditorColors.actionAccent else EditorColors.nodeBorder,
                shape = ROW_SHAPE,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.Psychology,
            contentDescription = null,
            tint = EditorColors.actionAccent,
            modifier = Modifier.size(20.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = profile.name.ifBlank { stringResource(R.string.ai_unnamed_model) },
                style = MaterialTheme.typography.bodyLarge,
                color = EditorColors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = profile.modelId.ifBlank { stringResource(profile.effort.labelRes()) },
                style = MaterialTheme.typography.bodySmall,
                color = EditorColors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun AddConnectionRow(onClick: () -> Unit) {
    val accent = EditorColors.actionAccent
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ROW_SHAPE)
            .border(1.dp, accent.copy(alpha = 0.5f), ROW_SHAPE)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.Filled.Add, contentDescription = null, tint = accent, modifier = Modifier.size(20.dp))
        Text(
            text = stringResource(R.string.ai_add_connection),
            style = MaterialTheme.typography.bodyLarge,
            color = accent,
            fontWeight = FontWeight.Medium,
        )
    }
}
