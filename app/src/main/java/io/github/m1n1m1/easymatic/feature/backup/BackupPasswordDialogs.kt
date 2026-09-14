package io.github.m1n1m1.easymatic.feature.backup

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.m1n1m1.easymatic.R
import io.github.m1n1m1.easymatic.data.security.PasswordCipher
import io.github.m1n1m1.easymatic.feature.geofence.darkFieldColors
import io.github.m1n1m1.easymatic.feature.grapheditor.EditorColors
import io.github.m1n1m1.easymatic.feature.macro.editorTextButtonColors
import kotlinx.coroutines.launch

/**
 * Asks for a backup password, typed twice, because it is never shown and a typo here
 * is a backup nobody can open. Before a backup is written the button says so
 * ([confirmRes]); when only the standing password is being changed it says Save.
 */
@Composable
fun SetBackupPasswordDialog(
    @StringRes confirmRes: Int,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var password by remember { mutableStateOf("") }
    var repeat by remember { mutableStateOf("") }
    val tooShort = password.isNotEmpty() && password.length < PasswordCipher.MIN_PASSWORD_LENGTH
    val mismatch = repeat.isNotEmpty() && repeat != password
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = EditorColors.chrome,
        title = { Text(stringResource(R.string.backup_password_dialog_title), color = EditorColors.textPrimary) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // Said before the field rather than after a failure: the file carries its
                // salt and verifier, so a short password is guessable by anyone holding it.
                Note(stringResource(R.string.backup_password_unrecoverable, PasswordCipher.MIN_PASSWORD_LENGTH))
                PasswordField(password, { password = it }, R.string.backup_password_field, isError = tooShort)
                if (tooShort) {
                    Note(stringResource(R.string.backup_password_too_short, PasswordCipher.MIN_PASSWORD_LENGTH))
                }
                PasswordField(repeat, { repeat = it }, R.string.backup_password_repeat, isError = mismatch)
                if (mismatch) Note(stringResource(R.string.backup_password_mismatch))
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(password) },
                enabled = password.length >= PasswordCipher.MIN_PASSWORD_LENGTH && password == repeat,
                colors = editorTextButtonColors(),
            ) { Text(stringResource(confirmRes)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, colors = editorTextButtonColors()) {
                Text(stringResource(R.string.backup_cancel))
            }
        },
    )
}

/**
 * Asks for the backup password that unlocks restored credentials. [onUnlock] answers
 * whether it was the right one; a wrong one keeps the dialog open and says so, since
 * closing it would send the user looking for what went wrong somewhere else.
 */
@Composable
fun UnlockBackupDialog(body: String, onUnlock: suspend (String) -> Boolean, onDismiss: () -> Unit) {
    var password by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = EditorColors.chrome,
        title = { Text(stringResource(R.string.backup_password_dialog_title), color = EditorColors.textPrimary) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Note(body)
                PasswordField(
                    value = password,
                    onValueChange = {
                        password = it
                        wrong = false
                    },
                    labelRes = R.string.backup_password_field,
                    isError = wrong,
                )
                if (wrong) Note(stringResource(R.string.backup_password_wrong))
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    scope.launch {
                        busy = true
                        val accepted = onUnlock(password)
                        busy = false
                        if (accepted) onDismiss() else wrong = true
                    }
                },
                enabled = password.isNotBlank() && !busy,
                colors = editorTextButtonColors(),
            ) { Text(stringResource(R.string.backup_password_unlock)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, colors = editorTextButtonColors()) {
                Text(stringResource(R.string.backup_password_later))
            }
        },
    )
}

/** One password line, shared by every dialog on the Backup screen that asks for one. */
@Composable
internal fun PasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    @StringRes labelRes: Int,
    isError: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(stringResource(labelRes)) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        isError = isError,
        colors = darkFieldColors(),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun Note(text: String) {
    Text(text = text, color = EditorColors.textPrimary, fontSize = 14.sp)
}
