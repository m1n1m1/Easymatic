package io.github.m1n1m1.easymatic.feature.mail

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
 * Picks the account a mail node uses, or adds a new one.
 *
 * Follows the deferred-pick idiom every chooser in the app uses — the choice is
 * held until the exit animation has played — and stacks the account editor above
 * itself the way the tag and place pickers stack theirs, including the "created
 * here, so selected on save" behaviour. That last part matters more here than
 * anywhere else: for most people the first account they ever add will be added
 * from inside this overlay, having dropped a Send Email node and found the Account
 * field empty.
 */
@Composable
fun MailAccountPickerOverlay(
    viewModel: MailAccountsViewModel,
    selectedId: String?,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val state by viewModel.uiState.collectAsState()
    var picked by remember { mutableStateOf<String?>(null) }

    EditorOverlay(
        title = stringResource(R.string.mail_choose_an_account),
        onClose = { picked?.let(onPick) ?: onDismiss() },
    ) { dismiss ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding(),
        ) {
            MailAccountList(
                accounts = state.accounts,
                selectedId = selectedId,
                needsPassword = viewModel::needsPassword,
                onSelect = { account ->
                    picked = account.id
                    dismiss()
                },
                onEdit = { account -> viewModel.editAccount(account.id) },
                onAdd = viewModel::addAccount,
            )
        }
    }

    state.draft?.let { draft ->
        MailAccountEditorOverlay(
            draft = draft,
            viewModel = viewModel,
            onClose = viewModel::closeEditor,
            onSaved = { savedId -> if (draft.isNew) onPick(savedId) },
        )
    }
}
