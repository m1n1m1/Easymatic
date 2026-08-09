package com.example.ottomatic.feature.mail

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.ottomatic.data.MailAccountRepository
import com.example.ottomatic.data.mail.MailTransport
import com.example.ottomatic.domain.model.MailAccount
import com.example.ottomatic.domain.model.MailProvider
import com.example.ottomatic.domain.model.MailSecurity
import com.example.ottomatic.engine.service.MacroEngineService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * An account being added or edited.
 *
 * The ports are **strings**, not `Int`s, which is a form concern rather than a
 * modelling one: a numeric field that cannot briefly be empty cannot be edited,
 * because clearing it to type a new value is exactly the state an `Int` has no
 * room for.
 *
 * [password] is write-only. It is blank whenever an existing account is opened —
 * never pre-filled with what is stored — and blank on save means "leave the stored
 * secret alone". That is what [com.example.ottomatic.data.MailAccountRepository]
 * means by never handing a plaintext password back to the UI, and it costs
 * nothing: nobody needs to read back a password they typed themselves.
 */
data class MailAccountDraft(
    val id: String = "",
    val name: String = "",
    val address: String = "",
    val username: String = "",
    val password: String = "",
    val provider: MailProvider = MailProvider.CUSTOM,
    val smtpHost: String = "",
    val smtpPort: String = MailAccount.DEFAULT_SUBMISSION_PORT.toString(),
    val smtpSecurity: MailSecurity = MailSecurity.STARTTLS,
    val imapHost: String = "",
    val imapPort: String = MailAccount.DEFAULT_IMAPS_PORT.toString(),
    val imapSecurity: MailSecurity = MailSecurity.TLS,
    val isNew: Boolean = true,
    /** The account survived a restore but its key did not — see the repository. */
    val needsPassword: Boolean = false,
    val testing: Boolean = false,
    /** Null until a connection test has run. */
    val testResult: String? = null,
    val testOk: Boolean = false,
) {

    /**
     * A new account must carry a password, because there is nothing stored to fall
     * back on; an existing one need not, because blank means "unchanged".
     * [MailProvider.usable] is in here so the Microsoft row's Save is dead rather
     * than saving something that can never sign in.
     */
    val canSave: Boolean
        get() = provider.usable &&
            name.isNotBlank() &&
            address.isNotBlank() &&
            smtpHost.isNotBlank() &&
            imapHost.isNotBlank() &&
            (!isNew || password.isNotBlank())

    /** A test needs a password from somewhere: typed now, or already stored. */
    val canTest: Boolean
        get() = provider.usable &&
            address.isNotBlank() &&
            smtpHost.isNotBlank() &&
            imapHost.isNotBlank() &&
            (password.isNotBlank() || (!isNew && !needsPassword))
}

data class MailAccountsUiState(
    val accounts: List<MailAccount> = emptyList(),
    val draft: MailAccountDraft? = null,
)

/**
 * Drives the mail account library: the list, and the editor for one account.
 *
 * Shared by the standalone Mail accounts screen and the picker every mail node's
 * Account field opens — see [LocalMailAccounts] for how a config form several
 * composables deep gets hold of it.
 *
 * **It re-arms the engine on save and delete**, which
 * [com.example.ottomatic.feature.nfc.NfcTagsViewModel] deliberately does not, and
 * the asymmetry is worth stating because it looks like a copy-paste slip. An armed
 * `trigger.mail` holds an open IMAP connection, opened with a host, port, username
 * and password read once when it activated — so changing any of them has to tear
 * that connection down and put a new one back, exactly as moving a geofence place
 * has to re-register the fence. A tag trigger holds nothing: it matches on the id a
 * tap carries and looks the name up as it fires.
 */
@Suppress("TooManyFunctions") // One entry point per control on the account form; the form sets the count.
class MailAccountsViewModel(
    private val repository: MailAccountRepository,
    private val appContext: Context,
) : ViewModel() {

    private val _uiState = MutableStateFlow(MailAccountsUiState(accounts = repository.list()))
    val uiState: StateFlow<MailAccountsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.accounts.collect { accounts -> _uiState.update { it.copy(accounts = accounts) } }
        }
    }

    /** What the picker shows for a stored id. */
    fun accountById(id: String): MailAccount? = repository.get(id)

    /** Whether [id] has lost its password — the list draws a chip for it. */
    fun needsPassword(id: String): Boolean = repository.needsPassword(id)

    fun addAccount() {
        _uiState.update { it.copy(draft = MailAccountDraft()) }
    }

    fun editAccount(id: String) {
        val account = repository.get(id) ?: return
        _uiState.update {
            it.copy(
                draft = MailAccountDraft(
                    id = account.id,
                    name = account.name,
                    address = account.address,
                    username = account.username,
                    provider = MailProvider.byName(account.preset) ?: MailProvider.CUSTOM,
                    smtpHost = account.smtpHost,
                    smtpPort = account.smtpPort.toString(),
                    smtpSecurity = account.smtpSecurity,
                    imapHost = account.imapHost,
                    imapPort = account.imapPort.toString(),
                    imapSecurity = account.imapSecurity,
                    isNew = false,
                    needsPassword = repository.needsPassword(account.id),
                ),
            )
        }
    }

    fun closeEditor() {
        _uiState.update { it.copy(draft = null) }
    }

    /**
     * The address changed. When the user has not overridden the preset, this also
     * re-guesses it — typing `…@gmail.com` fills six server fields in, which is the
     * whole reason presets exist. An explicit choice is never overwritten.
     */
    fun addressChanged(address: String) = editDraft { draft ->
        val guess = MailProvider.forAddress(address)
        if (draft.provider == MailProvider.CUSTOM && guess != MailProvider.CUSTOM) {
            draft.copy(address = address).withProvider(guess)
        } else {
            draft.copy(address = address)
        }
    }

    fun nameChanged(name: String) = editDraft { it.copy(name = name) }

    fun usernameChanged(username: String) = editDraft { it.copy(username = username) }

    fun passwordChanged(password: String) = editDraft {
        // Clearing the stale verdict matters: a green "Signed in" left standing
        // beside an edited password is a claim about a connection nobody made.
        it.copy(password = password, testResult = null, testOk = false)
    }

    fun providerChosen(provider: MailProvider) = editDraft { it.withProvider(provider) }

    fun smtpHostChanged(host: String) = editDraft { it.copy(smtpHost = host, provider = MailProvider.CUSTOM) }

    fun smtpPortChanged(port: String) = editDraft { it.copy(smtpPort = port.filter(Char::isDigit)) }

    fun smtpSecurityChanged(security: MailSecurity) = editDraft { it.copy(smtpSecurity = security) }

    fun imapHostChanged(host: String) = editDraft { it.copy(imapHost = host, provider = MailProvider.CUSTOM) }

    fun imapPortChanged(port: String) = editDraft { it.copy(imapPort = port.filter(Char::isDigit)) }

    fun imapSecurityChanged(security: MailSecurity) = editDraft { it.copy(imapSecurity = security) }

    /**
     * Signs in to both protocols and reports back.
     *
     * The one place a wrong host or a wrong app password can be reported while the
     * user is still looking at the form. Without it the first thing that ever tries
     * these settings is a macro, in the background, writing into a console nobody
     * has open.
     *
     * An unsaved account is probed from the draft rather than from disk, so the
     * button works before Save — which is the order anybody sane does this in.
     */
    fun testConnection() {
        val draft = _uiState.value.draft?.takeIf { it.canTest } ?: return
        _uiState.update { it.copy(draft = draft.copy(testing = true, testResult = null)) }
        viewModelScope.launch {
            val password = draft.password.ifBlank { repository.password(draft.id).orEmpty() }
            val problem = withContext(Dispatchers.IO) {
                MailTransport.probe(draft.toAccount(), password)
            }
            editDraft { it.copy(testing = false, testOk = problem == null, testResult = problem ?: SIGNED_IN) }
        }
    }

    /** Saves the open draft. [onSaved] receives the id, so a picker can select it. */
    fun save(onSaved: (String) -> Unit = {}) {
        val draft = _uiState.value.draft?.takeIf { it.canSave } ?: return
        viewModelScope.launch {
            val stored = if (draft.isNew) {
                repository.create(draft.toAccount())
            } else {
                repository.upsert(draft.toAccount().copy(secret = repository.get(draft.id)?.secret.orEmpty()))
            }
            // After the account exists, so a new one has an id to seal against.
            if (draft.password.isNotBlank()) repository.setPassword(stored.id, draft.password)
            rearm()
            closeEditor()
            onSaved(stored.id)
        }
    }

    fun delete(id: String) {
        viewModelScope.launch {
            repository.delete(id)
            rearm()
            if (_uiState.value.draft?.id == id) closeEditor()
        }
    }

    private fun editDraft(transform: (MailAccountDraft) -> MailAccountDraft) {
        _uiState.update { state -> state.copy(draft = state.draft?.let(transform)) }
    }

    private fun MailAccountDraft.withProvider(provider: MailProvider): MailAccountDraft =
        if (provider == MailProvider.CUSTOM) {
            copy(provider = provider)
        } else {
            copy(
                provider = provider,
                smtpHost = provider.smtpHost,
                smtpPort = provider.smtpPort.toString(),
                smtpSecurity = provider.smtpSecurity,
                imapHost = provider.imapHost,
                imapPort = provider.imapPort.toString(),
                imapSecurity = provider.imapSecurity,
            )
        }

    private fun MailAccountDraft.toAccount(): MailAccount = MailAccount(
        id = id,
        name = name.trim(),
        address = address.trim(),
        username = username.trim(),
        smtpHost = smtpHost.trim(),
        smtpPort = smtpPort.toIntOrNull() ?: MailAccount.DEFAULT_SUBMISSION_PORT,
        smtpSecurity = smtpSecurity,
        imapHost = imapHost.trim(),
        imapPort = imapPort.toIntOrNull() ?: MailAccount.DEFAULT_IMAPS_PORT,
        imapSecurity = imapSecurity,
        preset = provider.name,
        addedAtEpochMs = repository.get(id)?.addedAtEpochMs ?: System.currentTimeMillis(),
    )

    private fun rearm() {
        MacroEngineService.start(appContext, MacroEngineService.ACTION_REARM_CHANGED)
    }

    companion object {

        private const val SIGNED_IN = "Signed in. Sending and receiving both work."

        fun factory(
            repository: MailAccountRepository,
            appContext: Context,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { MailAccountsViewModel(repository, appContext) }
        }
    }
}
