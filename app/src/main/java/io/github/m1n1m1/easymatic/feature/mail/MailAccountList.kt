package io.github.m1n1m1.easymatic.feature.mail

import androidx.compose.ui.res.stringResource
import io.github.m1n1m1.easymatic.R
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Mail
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.m1n1m1.easymatic.domain.model.MailAccount
import io.github.m1n1m1.easymatic.feature.grapheditor.EditorColors

private val ROW_SHAPE = RoundedCornerShape(16.dp)

/**
 * The account library as a list, shared by the standalone Mail accounts screen and
 * the picker every mail node's Account field opens.
 *
 * Unlike `NfcTagList` there is **no "any account" row**, and the absence is
 * deliberate: blank is a real answer to "which tag should fire this?" and is not
 * one to "which account should this be sent from". A node with no account chosen is
 * unconfigured, and says so rather than guessing.
 */
@Composable
fun MailAccountList(
    accounts: List<MailAccount>,
    onSelect: (MailAccount) -> Unit,
    onEdit: (MailAccount) -> Unit,
    onAdd: () -> Unit,
    modifier: Modifier = Modifier,
    selectedId: String? = null,
    needsPassword: (String) -> Boolean = { false },
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "add") { AddAccountRow(onClick = onAdd) }
        if (accounts.isEmpty()) {
            item(key = "empty") {
                Text(
                    text = stringResource(R.string.mail_no_accounts_yet_add_one),
                    style = MaterialTheme.typography.bodyMedium,
                    color = EditorColors.textSecondary,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 16.dp),
                )
            }
        }
        items(accounts, key = { it.id }) { account ->
            AccountRow(
                account = account,
                selected = account.id == selectedId,
                needsPassword = needsPassword(account.id),
                onClick = { onSelect(account) },
                onEdit = { onEdit(account) },
            )
        }
    }
}

@Composable
private fun AddAccountRow(onClick: () -> Unit) {
    val accent = EditorColors.actionAccent
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ROW_SHAPE)
            .background(accent.copy(alpha = 0.08f))
            .border(1.dp, accent.copy(alpha = 0.35f), ROW_SHAPE)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.Filled.Add, contentDescription = null, tint = accent)
        Text(
            text = stringResource(R.string.mail_add_an_account),
            style = MaterialTheme.typography.bodyLarge,
            color = accent,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun AccountRow(
    account: MailAccount,
    selected: Boolean,
    needsPassword: Boolean,
    onClick: () -> Unit,
    onEdit: () -> Unit,
) {
    val accent = EditorColors.actionAccent
    val borderColor = when {
        selected -> accent
        // A missing password is drawn on the row rather than left for a failed send
        // to reveal: this is the state a restored phone lands in, and it looks
        // exactly like a working account until something tries to use it.
        needsPassword -> EditorColors.errorAccent
        else -> EditorColors.nodeBorder
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ROW_SHAPE)
            .background(EditorColors.nodeBackground)
            .border(if (selected) 2.dp else 1.dp, borderColor, ROW_SHAPE)
            .clickable(onClick = onClick)
            .padding(start = 14.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(accent.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.Mail,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(20.dp),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = account.name,
                style = MaterialTheme.typography.bodyLarge,
                color = EditorColors.textPrimary,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = if (needsPassword) stringResource(R.string.mail_password_needed) else account.address,
                style = MaterialTheme.typography.bodySmall,
                color = if (needsPassword) EditorColors.errorAccent else EditorColors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onEdit) {
            Icon(
                imageVector = Icons.Filled.Edit,
                contentDescription = stringResource(R.string.mail_edit_named, account.name),
                tint = EditorColors.textSecondary,
            )
        }
    }
}
