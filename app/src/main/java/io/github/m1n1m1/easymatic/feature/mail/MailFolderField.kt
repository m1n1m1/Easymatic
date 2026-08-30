package io.github.m1n1m1.easymatic.feature.mail

import androidx.compose.ui.res.stringResource
import io.github.m1n1m1.easymatic.R
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.m1n1m1.easymatic.feature.grapheditor.EditorColors
import io.github.m1n1m1.easymatic.feature.grapheditor.EditorOverlay

/**
 * A `@MailFolder` field: a mailbox name typed in, with a button that lists what is
 * actually on the server.
 *
 * **Editable, not a read-only picker**, and the field stays usable when the button
 * cannot be. Listing folders needs a network, a reachable host and a password the
 * phone can still read; a read-only field would be unfillable in exactly the
 * situations where the account is misconfigured, which is the failure
 * `@WifiNetwork` exists to avoid. The chooser is the convenience — the typing is
 * the guarantee.
 *
 * The button is what makes this worth having at all. Gmail's folders are bracketed
 * and localised, so a German account's archive is `[Gmail]/Alle Nachrichten`: a
 * name nobody guesses, and one that reads as a typo when it is correct.
 *
 * [accountId] is the sibling field's value. Blank means the node has no account
 * chosen yet — or, for `action.mail_update`, has none to choose — so the overlay
 * asks which account before it can ask the server anything.
 *
 * Reached from `SuggestedField` rather than from the form's `when` directly, because a mailbox
 * list is the one suggestion source that cannot be answered from a registry: it is an
 * authenticated IMAP `LIST`, so it needs a ViewModel, a spinner and a *rendered* failure. The
 * declaration a node writes is the same either way — see `@Suggested`.
 */
@Composable
internal fun MailFolderChooser(
    value: String,
    accountId: String,
    onValueChange: (String) -> Unit,
    labelSlot: @Composable () -> Unit,
    colors: TextFieldColors,
) {
    var browsing by remember { mutableStateOf(false) }
    val accounts = LocalMailAccounts.current

    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = labelSlot,
        colors = colors,
        singleLine = true,
        placeholder = { Text(stringResource(R.string.mail_inbox)) },
        trailingIcon = {
            IconButton(onClick = { browsing = true }, enabled = accounts != null) {
                Icon(
                    imageVector = Icons.Filled.Folder,
                    contentDescription = stringResource(R.string.mail_choose_a_folder),
                    tint = EditorColors.textSecondary,
                )
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )

    if (browsing && accounts != null) {
        MailFolderOverlay(
            viewModel = accounts,
            accountId = accountId,
            selected = value,
            onPick = { folder ->
                onValueChange(folder)
                browsing = false
            },
            onDismiss = { browsing = false },
        )
    }
}

/**
 * What is on the server, or why it could not be asked.
 *
 * The failure is rendered rather than swallowed, and it is rendered *here* rather
 * than as a toast, because every reason it can fail is something the user can act
 * on: no account chosen, a password that needs re-typing, no network, a host that
 * does not resolve. A chooser that just came back empty would look like an account
 * with no folders.
 */
@Composable
private fun MailFolderOverlay(
    viewModel: MailAccountsViewModel,
    accountId: String,
    selected: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var picked by remember { mutableStateOf<String?>(null) }
    var folders by remember { mutableStateOf<List<String>?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }

    // Resolved before the effect, which is a coroutine rather than a composition.
    val noAccountMessage = stringResource(R.string.mail_choose_account_first)
    val unreadableMessage = stringResource(R.string.mail_folder_list_unreadable)

    // Keyed on the account, so choosing a different one re-asks rather than
    // showing the previous account's mailboxes.
    LaunchedEffect(accountId) {
        if (accountId.isBlank()) {
            problem = noAccountMessage
            return@LaunchedEffect
        }
        viewModel.folders(accountId)
            .onSuccess { folders = it }
            .onFailure { problem = it.message ?: unreadableMessage }
    }

    EditorOverlay(
        title = stringResource(R.string.mail_choose_a_folder),
        onClose = { picked?.let(onPick) ?: onDismiss() },
    ) { dismiss ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding(),
        ) {
            when {
                problem != null -> Notice(problem.orEmpty())
                folders == null -> Loading()
                folders.orEmpty().isEmpty() -> Notice(stringResource(R.string.mail_no_usable_folders))
                else -> FolderList(
                    folders = folders.orEmpty(),
                    selected = selected,
                    onSelect = { folder ->
                        picked = folder
                        dismiss()
                    },
                )
            }
        }
    }
}

@Composable
private fun FolderList(folders: List<String>, selected: String, onSelect: (String) -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(folders, key = { it }) { folder ->
            FolderRow(
                folder = folder,
                selected = folder.equals(selected, ignoreCase = true),
                onClick = { onSelect(folder) },
            )
        }
    }
}

@Composable
private fun FolderRow(folder: String, selected: Boolean, onClick: () -> Unit) {
    val accent = EditorColors.actionAccent
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (selected) accent.copy(alpha = 0.16f) else EditorColors.nodeBackground)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.Folder,
            contentDescription = null,
            tint = if (selected) accent else EditorColors.textSecondary,
            modifier = Modifier.size(20.dp),
        )
        Text(
            text = folder,
            style = MaterialTheme.typography.bodyLarge,
            color = EditorColors.textPrimary,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun Loading() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = EditorColors.actionAccent)
    }
}

@Composable
private fun Notice(message: String) {
    Text(
        text = message,
        style = MaterialTheme.typography.bodyMedium,
        color = EditorColors.textSecondary,
        modifier = Modifier.padding(20.dp),
    )
}
