package io.github.m1n1m1.easymatic.feature.backup

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.m1n1m1.easymatic.R
import io.github.m1n1m1.easymatic.data.backup.BackupRepository
import io.github.m1n1m1.easymatic.data.backup.RestoreResult
import io.github.m1n1m1.easymatic.data.security.EscrowChallenge
import io.github.m1n1m1.easymatic.data.security.EscrowState
import io.github.m1n1m1.easymatic.data.security.SecretEscrow
import io.github.m1n1m1.easymatic.domain.backup.BackupCheck
import io.github.m1n1m1.easymatic.domain.backup.BackupManifest
import io.github.m1n1m1.easymatic.engine.service.MacroEngineService
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Everything the Backup screen draws. */
data class BackupUiState(
    val busy: Boolean = false,
    /** A file that has been read and is waiting for the user's password and yes. */
    val pending: PendingRestore? = null,
    /** The password typed into the confirmation was not the file's. */
    val wrongPassword: Boolean = false,
    /** What the last backup or restore did, until dismissed. */
    val message: BackupMessage? = null,
)

/** A chosen backup file, what its manifest says, and the password check its header carries. */
data class PendingRestore(val source: Uri, val manifest: BackupManifest, val challenge: EscrowChallenge)

/** What to tell the user about the backup or restore that just happened. */
sealed interface BackupMessage {

    data class Saved(val macros: Int) : BackupMessage

    /** [locked] when the file's credentials are still waiting for the backup password. */
    data class Restored(val added: Int, val copies: Int, val locked: Boolean) : BackupMessage

    data class Failed(val reason: BackupFailure) : BackupMessage
}

enum class BackupFailure { UNREADABLE, TOO_NEW, TOO_OLD, WRITE_FAILED, RESTORE_FAILED }

/**
 * The Backup screen's state: two document-picker round trips, each with a password.
 *
 * **A backup always has a password, and the whole file is under it.** Backing up asks
 * for one *before* the file picker opens; the password re-keys the escrow and the same
 * derivation encrypts the file written a moment later, so nothing in it is readable
 * without it, and Android's own backup carries the credentials under it from then on.
 * The picker hands back only a Uri, so the password waits in [stagedPassword] between
 * the two — in the ViewModel rather than the composition, so a rotation during the
 * picker does not lose it, and never on disk.
 *
 * Restoring asks for the file's password in the confirmation dialog and checks it
 * against the header's verifier **before anything is touched**; a wrong one keeps the
 * dialog open and says so. The cipher that check derived then decrypts the file and,
 * on a phone that did not make it, unlocks the restored escrow — so every credential is
 * back before the engine re-arms, and the password is derived once.
 *
 * What happens **after** a restore is this class's rather than the repository's, for the
 * package rule: `data` may not reach `engine`. The re-arm goes through
 * `ACTION_REARM_CHANGED`, which cancels, joins and re-arms every enabled macro — so the
 * ones already running pick up the replaced places, hubs and globals, and the ones just
 * added arm for the first time. [afterRestore] is the plugin registry's refresh, handed
 * in by `MainActivity` so this class holds no locator.
 */
@Suppress("TooManyFunctions") // One member per thing the screen can do; the screen sets the count.
class BackupViewModel(
    private val backups: BackupRepository,
    private val escrow: SecretEscrow,
    private val afterRestore: () -> Unit,
    private val appContext: Context,
) : ViewModel() {

    private val _uiState = MutableStateFlow(BackupUiState())
    val uiState: StateFlow<BackupUiState> = _uiState.asStateFlow()

    /** Whether a backup password is set, and whether this phone can read the escrow. */
    val escrowState: StateFlow<EscrowState> = escrow.state

    private var stagedPassword: String? = null

    /** The name to offer the document picker, dated today. */
    fun suggestedFileName(): String = backups.fileName()

    /** The password the next [backUp] will protect the file with. */
    fun stageBackupPassword(password: String) {
        stagedPassword = password
    }

    /** The picker was backed out of: the staged password protects nothing. */
    fun cancelBackup() {
        stagedPassword = null
    }

    /**
     * Writes a backup to [target] under the staged password. `"wt"` rather than `"w"`,
     * because the plain mode does not truncate: a shorter backup written over a longer
     * one would leave the tail of the old archive behind it.
     */
    fun backUp(target: Uri) {
        val password = stagedPassword ?: return
        stagedPassword = null
        viewModelScope.launch {
            busy {
                // Re-keyed first, so the escrow the archive copies is under this password,
                // and the file is encrypted with the same derivation.
                val cipher = escrow.setPassword(password)
                val stream = if (cipher != null) {
                    withContext(Dispatchers.IO) {
                        runCatching { appContext.contentResolver.openOutputStream(target, "wt") }.getOrNull()
                    }
                } else {
                    null
                }
                val manifest = if (stream != null && cipher != null) backups.write(stream, cipher) else null
                show(
                    if (manifest == null) {
                        BackupMessage.Failed(BackupFailure.WRITE_FAILED)
                    } else {
                        BackupMessage.Saved(manifest.macros)
                    },
                )
            }
        }
    }

    /** Changes the password Android's own backup carries the credentials under. */
    fun setPassword(password: String) {
        viewModelScope.launch {
            busy {
                if (escrow.setPassword(password) == null) show(BackupMessage.Failed(BackupFailure.WRITE_FAILED))
            }
        }
    }

    /** Whether [password] was the backup password; when it was, every locked credential is back. */
    suspend fun unlock(password: String): Boolean = escrow.unlock(password)

    /** Reads [source]'s manifest and, when it is a backup this build can restore, asks. */
    fun inspect(source: Uri) {
        viewModelScope.launch {
            busy {
                val inspection = backups.inspect { open(source) }
                val check = inspection.check
                val challenge = inspection.challenge
                if (check is BackupCheck.Ready && challenge != null) {
                    _uiState.update {
                        it.copy(pending = PendingRestore(source, check.manifest, challenge), wrongPassword = false)
                    }
                } else {
                    show(BackupMessage.Failed(failureOf(check)))
                }
            }
        }
    }

    /**
     * The user typed [password] and said yes: check it against the file first, then
     * restore, then unlock what the restore brought in, then bring the engine up to date.
     */
    fun confirmRestore(password: String) {
        val pending = _uiState.value.pending ?: return
        viewModelScope.launch {
            busy {
                val cipher = pending.challenge.open(password)
                if (cipher == null) {
                    _uiState.update { it.copy(wrongPassword = true) }
                    return@busy
                }
                _uiState.update { it.copy(pending = null, wrongPassword = false) }
                when (val result = backups.restore({ open(pending.source) }, cipher, ::copyName)) {
                    is RestoreResult.Done -> {
                        // Before the re-arm, so a restored hub is re-armed with its token.
                        if (escrow.state.value is EscrowState.Locked) escrow.unlockWith(cipher)
                        afterRestore()
                        MacroEngineService.start(appContext, MacroEngineService.ACTION_REARM_CHANGED)
                        val locked = escrow.state.value is EscrowState.Locked
                        show(BackupMessage.Restored(result.added, result.copies, locked))
                    }
                    is RestoreResult.Refused -> show(BackupMessage.Failed(failureOf(result.check)))
                    RestoreResult.Failed -> show(BackupMessage.Failed(BackupFailure.RESTORE_FAILED))
                }
            }
        }
    }

    fun cancelRestore() {
        _uiState.update { it.copy(pending = null, wrongPassword = false) }
    }

    fun dismiss() {
        _uiState.update { it.copy(message = null) }
    }

    private fun copyName(name: String): String = appContext.getString(R.string.macro_transfer_copy_suffix, name)

    private fun open(source: Uri): InputStream? =
        runCatching { appContext.contentResolver.openInputStream(source) }.getOrNull()

    private fun show(message: BackupMessage) {
        _uiState.update { it.copy(message = message) }
    }

    private suspend fun busy(block: suspend () -> Unit) {
        _uiState.update { it.copy(busy = true) }
        try {
            block()
        } finally {
            _uiState.update { it.copy(busy = false) }
        }
    }

    private fun failureOf(check: BackupCheck): BackupFailure = when (check) {
        is BackupCheck.TooNew -> BackupFailure.TOO_NEW
        is BackupCheck.TooOld -> BackupFailure.TOO_OLD
        is BackupCheck.Ready, BackupCheck.Unreadable -> BackupFailure.UNREADABLE
    }

    companion object {
        fun factory(
            backups: BackupRepository,
            escrow: SecretEscrow,
            afterRestore: () -> Unit,
            appContext: Context,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { BackupViewModel(backups, escrow, afterRestore, appContext.applicationContext) }
        }
    }
}
