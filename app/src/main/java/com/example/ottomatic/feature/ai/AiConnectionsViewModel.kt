package com.example.ottomatic.feature.ai

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.ottomatic.core.service.Ai
import com.example.ottomatic.core.service.AiRequest
import com.example.ottomatic.data.AiConnectionRepository
import com.example.ottomatic.domain.model.AiConnection
import com.example.ottomatic.domain.model.AiProvider
import com.example.ottomatic.domain.registry.AiConnections
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * One connection being added or edited.
 *
 * [key] is the field's contents and is **never** loaded from what is stored — the
 * repository does not hand it back, by design. A blank field on an existing
 * connection therefore means "leave the key alone", which is why [canSave] does not
 * require one unless the connection is new or its key has become unreadable.
 *
 * [message] and [failed] carry the outcome of the last save or test. One pair
 * rather than two, because the two are mutually exclusive in time and a second
 * message could only ever be stale.
 */
data class AiConnectionDraft(
    val id: String = "",
    val name: String = "",
    val provider: AiProvider = AiProvider.GEMINI,
    val key: String = "",
    val isNew: Boolean = true,
    val needsKey: Boolean = false,
    val busy: Boolean = false,
    val message: String = "",
    val failed: Boolean = false,
) {
    /**
     * A new connection needs a name and a key; an existing one needs only a name,
     * unless its stored key has become unreadable — the restored-phone case, where
     * saving without one would leave it just as broken as it was.
     */
    val canSave: Boolean
        get() = name.isNotBlank() && (key.isNotBlank() || (!isNew && !needsKey))
}

data class AiConnectionsUiState(
    val connections: List<AiConnection> = emptyList(),
    val draft: AiConnectionDraft? = null,
)

/**
 * Drives the AI connection library.
 *
 * **Activity-scoped**, unlike the single-key screen it replaces, and for
 * [com.example.ottomatic.feature.mail.MailAccountsViewModel]'s exact reason: a
 * connection added from inside an Ask AI node's picker has to be the connection the
 * standalone screen is showing, since "paste the key again" is a fix somebody may
 * reach for from either place.
 *
 * **The test button is why this holds an [Ai] at all**, and it earns its place: a
 * mistyped or already-revoked key is otherwise discovered by a macro failing
 * silently at three in the morning, which is exactly the failure this whole
 * integration is meant not to have. It sends the shortest prompt that still proves
 * the round trip — the key, the network, the model and the parsing.
 */
@Suppress("TooManyFunctions") // One member per thing the form does; the editor's fields set the count.
class AiConnectionsViewModel(
    private val repository: AiConnectionRepository,
    private val ai: Ai,
) : ViewModel() {

    private val state = MutableStateFlow(AiConnectionsUiState(connections = repository.list()))

    val uiState: StateFlow<AiConnectionsUiState> = state.asStateFlow()

    init {
        viewModelScope.launch {
            repository.connections.collect { connections ->
                state.value = state.value.copy(connections = connections)
                // Republished here rather than only from ServiceLocator so the
                // Problems panel updates the moment a connection is deleted from
                // this screen, without waiting for anything else to notice.
                AiConnections.hydrate(connections.map { it.id })
            }
        }
    }

    /** The connection with [id], for the picker field's display name. */
    fun connectionById(id: String): AiConnection? = repository.get(id)

    /** Whether [id]'s key is missing or unreadable — badged on the row. */
    fun needsKey(id: String): Boolean = repository.needsKey(id)

    fun addConnection() {
        state.value = state.value.copy(draft = AiConnectionDraft(name = suggestedName()))
    }

    fun editConnection(id: String) {
        val connection = repository.get(id) ?: return
        state.value = state.value.copy(
            draft = AiConnectionDraft(
                id = connection.id,
                name = connection.name,
                provider = connection.provider,
                isNew = false,
                needsKey = repository.needsKey(connection.id),
            ),
        )
    }

    fun closeEditor() {
        state.value = state.value.copy(draft = null)
    }

    fun onNameChange(value: String) = editDraft { it.copy(name = value, message = "", failed = false) }

    fun onKeyChange(value: String) = editDraft { it.copy(key = value, message = "", failed = false) }

    fun onProviderChange(value: AiProvider) = editDraft { it.copy(provider = value) }

    /**
     * Persists the draft, then the key if one was typed.
     *
     * The connection is written **before** the key, and stays written even if
     * sealing fails, which is `SmartHomeSetup`'s ordering for a weaker version of
     * its reason: a half-finished connection the user can come back to and paste a
     * key into is a better outcome than a form that discards a name and a provider
     * because the keystore hiccuped.
     */
    fun save(onSaved: (String) -> Unit = {}) {
        val draft = state.value.draft ?: return
        if (!draft.canSave) return
        editDraft { it.copy(busy = true) }
        viewModelScope.launch {
            val connection = if (draft.isNew) {
                repository.create(draft.name.trim(), draft.provider)
            } else {
                repository.upsert(
                    repository.get(draft.id)?.copy(name = draft.name.trim(), provider = draft.provider)
                        ?: return@launch,
                )
            }
            val keyStored = draft.key.isBlank() || repository.setKey(connection.id, draft.key)
            if (keyStored) {
                state.value = state.value.copy(draft = null)
                onSaved(connection.id)
            } else {
                editDraft {
                    it.copy(
                        id = connection.id,
                        isNew = false,
                        busy = false,
                        message = "This phone would not store the key securely — the key was not saved.",
                        failed = true,
                    )
                }
            }
        }
    }

    fun delete(id: String) {
        viewModelScope.launch {
            repository.delete(id)
            if (state.value.draft?.id == id) closeEditor()
        }
    }

    /**
     * Asks the model the cheapest question there is, through the connection being
     * edited, and reports what came back.
     *
     * The reply's *text* is shown rather than a bare tick, because the useful
     * failure is the one where everything succeeds and the answer is nonsense — and
     * because seeing the model actually say something is what makes the feature
     * believable before any macro has been built on it.
     */
    fun test() {
        val draft = state.value.draft ?: return
        if (draft.id.isBlank()) return
        editDraft { it.copy(busy = true, message = "Asking the model…", failed = false) }
        viewModelScope.launch {
            val reply = ai.complete(
                AiRequest(connectionId = draft.id, prompt = TEST_PROMPT, maxOutputTokens = TEST_MAX_TOKENS),
            )
            editDraft {
                it.copy(
                    busy = false,
                    message = if (reply.error.isBlank()) {
                        "The model answered: ${reply.text.trim()}"
                    } else {
                        reply.error
                    },
                    failed = reply.error.isNotBlank(),
                )
            }
        }
    }

    private fun editDraft(transform: (AiConnectionDraft) -> AiConnectionDraft) {
        state.value.draft?.let { state.value = state.value.copy(draft = transform(it)) }
    }

    /** "Gemini", then "Gemini 2" and so on — a name the user can accept without typing. */
    private fun suggestedName(): String {
        val taken = repository.list().map { it.name }.toSet()
        if (DEFAULT_NAME !in taken) return DEFAULT_NAME
        return generateSequence(2) { it + 1 }.first { "$DEFAULT_NAME $it" !in taken }.let { "$DEFAULT_NAME $it" }
    }

    companion object {
        private const val DEFAULT_NAME = "Gemini"
        private const val TEST_PROMPT = "Reply with the single word: working"

        /** Enough for the one word asked for, and nothing near enough for a paragraph. */
        private const val TEST_MAX_TOKENS = 32

        fun factory(
            repository: AiConnectionRepository,
            ai: Ai,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { AiConnectionsViewModel(repository, ai) }
        }
    }
}
