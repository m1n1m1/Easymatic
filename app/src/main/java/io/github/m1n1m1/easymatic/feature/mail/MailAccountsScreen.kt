package io.github.m1n1m1.easymatic.feature.mail

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
 * The standalone mail account library, reached from the workflow list's overflow.
 *
 * Same argument as the Geofences and Tags screens: a library shared across macros
 * needs somewhere to be audited and pruned that is not inside one node's config
 * form. It carries one job neither of those has, though — a password expires or is
 * revoked, and when it does, every macro using the account stops at once. This is
 * where that is visible and where it is fixed.
 */
@Composable
fun MailAccountsScreen(
    viewModel: MailAccountsViewModel,
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
                title = stringResource(R.string.mail_mail_accounts),
                contentDescription = stringResource(R.string.mail_back),
                onBack = onBack,
            )

            MailAccountList(
                accounts = state.accounts,
                // Nothing to pick here, so tapping a row and tapping the pencil are
                // the same act — as on the Tags screen.
                onSelect = { account -> viewModel.editAccount(account.id) },
                onEdit = { account -> viewModel.editAccount(account.id) },
                onAdd = viewModel::addAccount,
                needsPassword = viewModel::needsPassword,
                modifier = Modifier.navigationBarsPadding(),
            )
        }
    }

    state.draft?.let { draft ->
        MailAccountEditorOverlay(
            draft = draft,
            viewModel = viewModel,
            onClose = viewModel::closeEditor,
        )
    }
}
