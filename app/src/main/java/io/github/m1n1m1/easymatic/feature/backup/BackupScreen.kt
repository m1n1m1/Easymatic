package io.github.m1n1m1.easymatic.feature.backup

import android.text.format.DateFormat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.m1n1m1.easymatic.R
import io.github.m1n1m1.easymatic.data.security.EscrowState
import io.github.m1n1m1.easymatic.feature.SettingsTopBar
import io.github.m1n1m1.easymatic.feature.grapheditor.EditorColors
import io.github.m1n1m1.easymatic.feature.macro.editorTextButtonColors
import io.github.m1n1m1.easymatic.feature.workflowlist.IMPORT_MIME_TYPES
import java.util.Date

/**
 * Everything on the phone to one file, and back.
 *
 * Three cards before the two buttons, because each says something the buttons cannot:
 * what a backup holds and what it leaves out, where the backup password stands, and
 * that Android carries the same set on its own. Every backup has a password: backing up
 * asks for one before the picker opens, and restoring asks for the file's in the
 * confirmation, checked before anything is touched. The password card is the one with
 * state: it offers to set or change the password Android's own backup uses, or to type
 * the one a restored backup was made with. Replacing the libraries is the one thing on
 * this screen that cannot be undone from inside the app, which is what the confirmation
 * is for.
 */
@Composable
fun BackupScreen(
    viewModel: BackupViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.uiState.collectAsState()
    val escrow by viewModel.escrowState.collectAsState()
    var askingBackupPassword by remember { mutableStateOf(false) }
    var changingPassword by remember { mutableStateOf(false) }
    var unlocking by remember { mutableStateOf(false) }

    // A cancelled picker does nothing at all, on `rememberMacroExport`'s rule: backing
    // out of a file chooser is not a failure and must not be reported as one — but the
    // password staged for it must not lie in wait for the next one.
    val save = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(BACKUP_MIME_TYPE),
    ) { target -> if (target != null) viewModel.backUp(target) else viewModel.cancelBackup() }

    val pick = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { source -> if (source != null) viewModel.inspect(source) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(EditorColors.canvasBackground),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            SettingsTopBar(
                title = stringResource(R.string.backup_title),
                contentDescription = stringResource(R.string.backup_back),
                onBack = onBack,
            )

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .navigationBarsPadding(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    NoteCard(
                        titleRes = R.string.backup_holds_title,
                        bodyRes = listOf(R.string.backup_holds_body, R.string.backup_not_included_body),
                    )
                }
                item {
                    PasswordCard(
                        state = escrow,
                        onSet = { changingPassword = true },
                        onEnter = { unlocking = true },
                    )
                }
                item { NoteCard(R.string.backup_android_title, listOf(R.string.backup_android_body)) }
                item {
                    // Not while locked: re-keying the escrow would drop the credentials that
                    // are still waiting for their password, and a backup made now would lose them.
                    Button(
                        onClick = { askingBackupPassword = true },
                        enabled = !state.busy && escrow !is EscrowState.Locked,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.backup_save))
                    }
                }
                item {
                    // Every type rather than `application/zip`, for `IMPORT_MIME_TYPES`'
                    // reason: a file that has been through a messenger or a download
                    // folder frequently arrives with no type recorded at all.
                    OutlinedButton(
                        onClick = { pick.launch(IMPORT_MIME_TYPES) },
                        enabled = !state.busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.backup_restore))
                    }
                }
                if (state.busy) {
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            CircularProgressIndicator()
                        }
                    }
                }
            }
        }
    }

    state.pending?.let { pending ->
        RestoreConfirmDialog(
            pending = pending,
            busy = state.busy,
            wrongPassword = state.wrongPassword,
            onConfirm = viewModel::confirmRestore,
            onCancel = viewModel::cancelRestore,
        )
    }
    state.message?.let { message ->
        BackupResultDialog(
            message = message,
            passwordUnset = escrow is EscrowState.Unset,
            onDismiss = viewModel::dismiss,
        )
    }
    if (askingBackupPassword) {
        SetBackupPasswordDialog(
            confirmRes = R.string.backup_password_continue,
            onConfirm = { password ->
                askingBackupPassword = false
                viewModel.stageBackupPassword(password)
                save.launch(viewModel.suggestedFileName())
            },
            onDismiss = { askingBackupPassword = false },
        )
    }
    if (changingPassword) {
        SetBackupPasswordDialog(
            confirmRes = R.string.backup_password_save,
            onConfirm = { password ->
                changingPassword = false
                viewModel.setPassword(password)
            },
            onDismiss = { changingPassword = false },
        )
    }
    if (unlocking) {
        UnlockBackupDialog(
            body = stringResource(R.string.backup_password_locked_body),
            onUnlock = viewModel::unlock,
            onDismiss = { unlocking = false },
        )
    }
}

/**
 * Where the backup password stands, and the one thing to do about it from here.
 *
 * Three states, three sentences: none set yet (the next backup sets it, or the button
 * does, for somebody relying on Android's backup alone), set (and kept up to date
 * without asking), or locked — the escrow came from a backup and this phone cannot read
 * it until the password is typed.
 */
@Composable
private fun PasswordCard(
    state: EscrowState,
    onSet: () -> Unit,
    onEnter: () -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = EditorColors.chrome)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = stringResource(R.string.backup_secrets_title),
                color = EditorColors.textPrimary,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
            )
            Text(
                text = stringResource(
                    when (state) {
                        EscrowState.Unset -> R.string.backup_secrets_body
                        is EscrowState.Unlocked -> R.string.backup_password_set_body
                        is EscrowState.Locked -> R.string.backup_password_locked_body
                    },
                ),
                color = EditorColors.textSecondary,
                fontSize = 13.sp,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (state) {
                    EscrowState.Unset -> TextButton(onClick = onSet, colors = editorTextButtonColors()) {
                        Text(stringResource(R.string.backup_password_set))
                    }
                    is EscrowState.Unlocked -> TextButton(onClick = onSet, colors = editorTextButtonColors()) {
                        Text(stringResource(R.string.backup_password_change))
                    }
                    is EscrowState.Locked -> TextButton(onClick = onEnter, colors = editorTextButtonColors()) {
                        Text(stringResource(R.string.backup_password_enter))
                    }
                }
            }
        }
    }
}

@Composable
private fun NoteCard(@StringRes titleRes: Int, bodyRes: List<Int>) {
    Card(colors = CardDefaults.cardColors(containerColor = EditorColors.chrome)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = stringResource(titleRes),
                color = EditorColors.textPrimary,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
            )
            bodyRes.forEach { res ->
                Text(
                    text = stringResource(res),
                    color = EditorColors.textSecondary,
                    fontSize = 13.sp,
                )
            }
        }
    }
}

/**
 * Says what the file holds and what restoring it does, asks for the file's password,
 * then asks. The password is checked before the dialog closes, so a wrong one is
 * answered here rather than by a restore that has already replaced the libraries.
 *
 * The date is the phone's own short date format: a backup made on this phone is read
 * back on it more often than not, and "14/09/2026" in the user's own order is what they
 * saw in the file manager a moment ago.
 */
@Composable
private fun RestoreConfirmDialog(
    pending: PendingRestore,
    busy: Boolean,
    wrongPassword: Boolean,
    onConfirm: (String) -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    val manifest = pending.manifest
    val date = remember(manifest.createdAtMs) {
        DateFormat.getDateFormat(context).format(Date(manifest.createdAtMs))
    }
    var password by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onCancel,
        containerColor = EditorColors.chrome,
        title = { Text(stringResource(R.string.backup_confirm_title), color = EditorColors.textPrimary) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Line(stringResource(R.string.backup_confirm_body, manifest.macros, date, manifest.appVersion))
                Line(stringResource(R.string.backup_confirm_effect))
                Line(stringResource(R.string.backup_confirm_password))
                PasswordField(
                    value = password,
                    onValueChange = { password = it },
                    labelRes = R.string.backup_password_field,
                    isError = wrongPassword,
                )
                if (wrongPassword) Line(stringResource(R.string.backup_password_wrong))
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(password) },
                enabled = !busy && password.isNotBlank(),
                colors = editorTextButtonColors(),
            ) {
                Text(stringResource(R.string.backup_confirm_action))
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel, colors = editorTextButtonColors()) {
                Text(stringResource(R.string.backup_cancel))
            }
        },
    )
}

/**
 * What the backup or restore just did, in `TransferDialog`'s shape and for its reason: a
 * restore has more than one thing to report, and none of them is glanceable.
 *
 * The line about credentials depends on where they stand: locked behind the backup
 * password, or — with no password set at all — needing to be typed again on a phone
 * other than the one that made the file. When a password is set and the escrow opened,
 * there is nothing to say.
 */
@Composable
private fun BackupResultDialog(message: BackupMessage, passwordUnset: Boolean, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = EditorColors.chrome,
        title = { Text(stringResource(titleOf(message)), color = EditorColors.textPrimary) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (message) {
                    is BackupMessage.Saved -> Line(stringResource(R.string.backup_saved_body, message.macros))
                    is BackupMessage.Restored -> {
                        Line(stringResource(R.string.backup_restored_body, message.added, message.copies))
                        if (message.locked) {
                            Line(stringResource(R.string.backup_restored_locked))
                        } else if (passwordUnset) {
                            Line(stringResource(R.string.backup_restored_secrets))
                        }
                    }
                    is BackupMessage.Failed -> Unit
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, colors = editorTextButtonColors()) {
                Text(stringResource(R.string.backup_ok))
            }
        },
    )
}

@Composable
private fun Line(text: String) {
    Text(text = text, color = EditorColors.textPrimary, fontSize = 14.sp)
}

@StringRes
private fun titleOf(message: BackupMessage): Int = when (message) {
    is BackupMessage.Saved -> R.string.backup_saved_title
    is BackupMessage.Restored -> R.string.backup_restored_title
    is BackupMessage.Failed -> when (message.reason) {
        BackupFailure.UNREADABLE -> R.string.backup_unreadable
        BackupFailure.TOO_NEW -> R.string.backup_too_new
        BackupFailure.TOO_OLD -> R.string.backup_too_old
        BackupFailure.WRITE_FAILED -> R.string.backup_save_failed
        BackupFailure.RESTORE_FAILED -> R.string.backup_restore_failed
    }
}

/**
 * What the "save as" picker is told the file is. Not `application/zip`: the file is a
 * manifest line and ciphertext, and a provider handed that type would append `.zip` to
 * the name and a file manager would offer to unzip it. Octet-stream is left alone.
 */
private const val BACKUP_MIME_TYPE = "application/octet-stream"
