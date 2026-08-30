package io.github.m1n1m1.easymatic.feature.mail

import androidx.annotation.StringRes
import androidx.compose.ui.res.stringResource
import io.github.m1n1m1.easymatic.R
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import io.github.m1n1m1.easymatic.domain.model.MailProvider
import io.github.m1n1m1.easymatic.domain.model.MailSecurity
import io.github.m1n1m1.easymatic.feature.grapheditor.EditorColors
import io.github.m1n1m1.easymatic.feature.grapheditor.EditorOverlay

/**
 * Adds or edits one mail account.
 *
 * Three things here are not ordinary form-filling, and each exists because of a
 * failure that is otherwise silent and hours away from its cause.
 *
 * **The provider dropdown** fills six server fields from an address, because a
 * hostname and a port are things nobody should have to look up — and because
 * choosing a provider is the only place its [MailProvider.note] can be read, which
 * is where "your normal password will not work here" is said. That is the failure
 * every single user hits first.
 *
 * **[MailProvider.MICROSOFT] renders a refusal rather than a form.** Microsoft
 * withdrew password sign-in for IMAP and SMTP, so there is no combination of fields
 * that could be typed here that would work. Saying so is the same stance
 * [io.github.m1n1m1.easymatic.domain.model.WebUrl] takes toward text that is not a URL:
 * refuse and name the reason, rather than hand it to the platform and let an
 * `AUTHENTICATE failed` stand in for an explanation.
 *
 * **Test connection** signs in to both protocols now, while the person who typed
 * the password is still looking at it. Without it, the first thing that ever tries
 * these settings is a macro, in the background, at whatever hour it was scheduled
 * for.
 */
@Composable
fun MailAccountEditorOverlay(
    draft: MailAccountDraft,
    viewModel: MailAccountsViewModel,
    onClose: () -> Unit,
    onSaved: (String) -> Unit = {},
) {
    EditorOverlay(
        title = stringResource(
            if (draft.isNew) R.string.mail_add_account else R.string.mail_edit_account,
        ),
        onClose = onClose,
        action = {
            if (!draft.isNew) {
                IconButton(onClick = { viewModel.delete(draft.id) }) {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = stringResource(R.string.mail_delete_account),
                        tint = EditorColors.textSecondary,
                    )
                }
            }
            TextButton(onClick = { viewModel.save(onSaved) }, enabled = draft.canSave) {
                Text(stringResource(R.string.mail_save))
            }
        },
    ) { _ ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ProviderField(draft.provider, viewModel::providerChosen)

            if (!draft.provider.usable) {
                RefusalCard(draft.provider.note)
                return@Column
            }

            if (draft.provider.note.isNotBlank()) {
                ProviderNote(draft.provider)
            }

            OutlinedTextField(
                value = draft.name,
                onValueChange = viewModel::nameChanged,
                label = { Text(stringResource(R.string.mail_name)) },
                placeholder = { Text(stringResource(R.string.mail_work_personal_alarm_system)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = draft.address,
                onValueChange = viewModel::addressChanged,
                label = { Text(stringResource(R.string.mail_email_address)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                modifier = Modifier.fillMaxWidth(),
            )
            PasswordField(draft, viewModel)
            OutlinedTextField(
                value = draft.username,
                onValueChange = viewModel::usernameChanged,
                label = { Text(stringResource(R.string.mail_username_only_if_it_differs)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            ServerFields(draft, viewModel)
            TestConnection(draft, viewModel)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProviderField(selected: MailProvider, onChoose: (MailProvider) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selected.label,
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.mail_provider)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            MailProvider.entries.forEach { provider ->
                DropdownMenuItem(
                    text = { Text(provider.label) },
                    onClick = {
                        onChoose(provider)
                        expanded = false
                    },
                )
            }
        }
    }
}

/** What a provider needs before it will work, plus a way to the page that provides it. */
@Composable
private fun ProviderNote(provider: MailProvider) {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = provider.note,
            style = MaterialTheme.typography.bodyMedium,
            color = EditorColors.textSecondary,
        )
        if (provider.appPasswordUrl.isNotBlank()) {
            TextButton(
                onClick = {
                    // The stored value is a bare host, so the scheme is added here
                    // rather than typed into six enum rows. https always: these are
                    // all sign-in pages.
                    val intent = Intent(Intent.ACTION_VIEW, "https://${provider.appPasswordUrl}".toUri())
                    runCatching { context.startActivity(intent) }
                },
                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
            ) {
                Text(stringResource(R.string.mail_open_the_app_password_page))
            }
        }
    }
}

/** The provider that cannot be made to work, saying so instead of offering a form. */
@Composable
private fun RefusalCard(message: String) {
    val accent = EditorColors.errorAccent
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(accent.copy(alpha = 0.08f))
            .border(1.dp, accent.copy(alpha = 0.35f), RoundedCornerShape(16.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = stringResource(R.string.mail_this_provider_cannot_be_added),
            style = MaterialTheme.typography.titleSmall,
            color = accent,
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = EditorColors.textSecondary,
        )
    }
}

/**
 * The password, written and never read back.
 *
 * On an existing account the field opens **blank** rather than pre-filled, and
 * blank on save means "leave the stored one alone" — which is why the placeholder
 * has to say so, or an empty box reads as a password that has been lost.
 */
@Composable
private fun PasswordField(draft: MailAccountDraft, viewModel: MailAccountsViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(
            value = draft.password,
            onValueChange = viewModel::passwordChanged,
            label = {
                Text(
                    stringResource(
                        if (draft.isNew) R.string.mail_app_password else R.string.mail_app_password_keep,
                    ),
                )
            },
            placeholder = { if (!draft.isNew && !draft.needsPassword) Text(stringResource(R.string.mail_unchanged)) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            isError = draft.needsPassword && draft.password.isBlank(),
            modifier = Modifier.fillMaxWidth(),
        )
        // The state a restored phone lands in. The account came back from backup;
        // the key that sealed its password did not, because a keystore key never
        // leaves the device it was made on.
        if (draft.needsPassword) {
            Text(
                text = stringResource(R.string.mail_this_account_s_password_could),
                style = MaterialTheme.typography.bodySmall,
                color = EditorColors.errorAccent,
            )
        }
    }
}

@Composable
private fun ServerFields(draft: MailAccountDraft, viewModel: MailAccountsViewModel) {
    Text(
        text = stringResource(R.string.mail_servers),
        style = MaterialTheme.typography.titleSmall,
        color = EditorColors.textPrimary,
    )
    HostRow(
        label = stringResource(R.string.mail_outgoing_smtp),
        host = draft.smtpHost,
        port = draft.smtpPort,
        security = draft.smtpSecurity,
        onHostChange = viewModel::smtpHostChanged,
        onPortChange = viewModel::smtpPortChanged,
        onSecurityChange = viewModel::smtpSecurityChanged,
    )
    HostRow(
        label = stringResource(R.string.mail_incoming_imap),
        host = draft.imapHost,
        port = draft.imapPort,
        security = draft.imapSecurity,
        onHostChange = viewModel::imapHostChanged,
        onPortChange = viewModel::imapPortChanged,
        onSecurityChange = viewModel::imapSecurityChanged,
    )
}

@Suppress("LongParameterList") // A host, a port and a security mode, times two callbacks each.
@Composable
private fun HostRow(
    label: String,
    host: String,
    port: String,
    security: MailSecurity,
    onHostChange: (String) -> Unit,
    onPortChange: (String) -> Unit,
    onSecurityChange: (MailSecurity) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = host,
                onValueChange = onHostChange,
                label = { Text(label) },
                singleLine = true,
                modifier = Modifier.weight(2f),
            )
            OutlinedTextField(
                value = port,
                onValueChange = onPortChange,
                label = { Text(stringResource(R.string.mail_port)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
            )
        }
        SecurityField(security, onSecurityChange)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SecurityField(selected: MailSecurity, onChoose: (MailSecurity) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = stringResource(securityLabelRes(selected)),
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.mail_security)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            MailSecurity.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(stringResource(securityLabelRes(option))) },
                    onClick = {
                        onChoose(option)
                        expanded = false
                    },
                )
            }
        }
    }
}

// Spelled here rather than read off the `@Label` annotations, which exist for the
// node config form and are derived through a serialization descriptor this screen
// has no reason to hold.
@StringRes
private fun securityLabelRes(security: MailSecurity): Int = when (security) {
    MailSecurity.NONE -> R.string.mail_security_none
    MailSecurity.STARTTLS -> R.string.mail_security_starttls
    MailSecurity.TLS -> R.string.mail_security_tls
}

@Composable
private fun TestConnection(draft: MailAccountDraft, viewModel: MailAccountsViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TextButton(onClick = viewModel::testConnection, enabled = draft.canTest && !draft.testing) {
                Text(stringResource(R.string.mail_test_connection))
            }
            if (draft.testing) {
                CircularProgressIndicator(
                    color = EditorColors.actionAccent,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        draft.testResult?.let { result ->
            Text(
                text = result,
                style = MaterialTheme.typography.bodyMedium,
                color = if (draft.testOk) EditorColors.textSecondary else EditorColors.errorAccent,
            )
        }
    }
}
