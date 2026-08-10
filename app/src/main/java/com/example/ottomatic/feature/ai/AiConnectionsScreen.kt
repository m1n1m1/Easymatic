package com.example.ottomatic.feature.ai

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
                            contentDescription = "Back",
                            tint = EditorColors.textPrimary,
                        )
                    }
                    Text(
                        text = "AI connections",
                        color = EditorColors.textPrimary,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
            }

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
