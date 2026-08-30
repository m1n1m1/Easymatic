package io.github.m1n1m1.easymatic.feature.ai

import androidx.compose.ui.res.stringResource
import io.github.m1n1m1.easymatic.R
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
import io.github.m1n1m1.easymatic.feature.SettingsTopBar
import io.github.m1n1m1.easymatic.feature.grapheditor.EditorColors

/**
 * The standalone AI connection library, reached from the workflow list's overflow.
 *
 * The same argument as the Geofences, Tags, Mail accounts and Smart home screens: a
 * library shared across macros needs somewhere to be audited and pruned that is not
 * inside one node's config form. It carries mail's extra job for mail's reason — a
 * key can be revoked or exhausted, and when it is, every macro using it stops at
 * once — and adds one of its own, which is that a key is a thing most people have
 * never made. The editor walks them through it.
 *
 * **This started as a single-key settings page** and argued in its own KDoc that
 * one key per phone was the whole model. Two things overturned that: a second
 * provider is a matter of when rather than whether, and quota is per key, so
 * separating a macro that fires every five minutes from one that summarises a mail
 * is a real use for a second key with one provider.
 */
@Composable
fun AiConnectionsScreen(
    viewModel: AiConnectionsViewModel,
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
                title = stringResource(R.string.ai_ai_connections),
                contentDescription = stringResource(R.string.ai_back),
                onBack = onBack,
            )

            AiConnectionList(
                connections = state.connections,
                // Nothing to pick here, so tapping a row and tapping the pencil are
                // the same act — as on the Mail accounts and Tags screens.
                onSelect = { connection -> viewModel.editConnection(connection.id) },
                onEdit = { connection -> viewModel.editConnection(connection.id) },
                onAdd = viewModel::addConnection,
                needsKey = viewModel::needsKey,
                modifier = Modifier.navigationBarsPadding(),
            )
        }
    }

    state.draft?.let { draft ->
        AiConnectionEditorOverlay(
            draft = draft,
            viewModel = viewModel,
            onClose = viewModel::closeEditor,
        )
    }
}
