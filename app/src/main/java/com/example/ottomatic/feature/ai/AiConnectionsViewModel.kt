package com.example.ottomatic.feature.ai

import android.content.Context
import com.example.ottomatic.R
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.ottomatic.core.service.Ai
import com.example.ottomatic.core.service.AiModel
import com.example.ottomatic.core.service.AiRequest
import com.example.ottomatic.data.AiConnectionRepository
import com.example.ottomatic.data.ai.AiModelCatalog
import com.example.ottomatic.domain.model.AiBaseUrl
import com.example.ottomatic.domain.model.AiConnection
import com.example.ottomatic.domain.model.AiProvider
import com.example.ottomatic.domain.model.isConfigured
import com.example.ottomatic.domain.model.needsBaseUrl
import com.example.ottomatic.domain.model.needsModelIds
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
 * [message] and [failed] carry the outcome of the last save, test or model listing.
 * One pair rather than three, because they are mutually exclusive in time and a
 * second message could only ever be stale.
 */
data class AiConnectionDraft(
    val id: String = "",
    val name: String = "",
    val provider: AiProvider = AiProvider.GEMINI,
    val key: String = "",
    val systemPrompt: String = "",
    val baseUrl: String = "",
    val fastModel: String = "",
    val balancedModel: String = "",
    val thoroughModel: String = "",
    val isNew: Boolean = true,
    val needsKey: Boolean = false,
    val busy: Boolean = false,
    val message: String = "",
    val failed: Boolean = false,
    /** The listing from this connection's own server, once somebody has asked for it. */
    val models: List<String> = emptyList(),
    /** Which speed setting the open chooser is filling in, or null when none is open. */
    val choosingFor: AiModel? = null,
) {
    /**
     * A new connection needs a name and a key; an existing one needs only a name,
     * unless its stored key has become unreadable — the restored-phone case, where
     * saving without one would leave it just as broken as it was.
     *
     * The two provider-shaped requirements are read off the same rules the Problems
     * panel and the facade use, rather than restated here: a self-hosted connection
     * needs somewhere to send requests, and a provider with no model table needs the
     * model named. Saving without them is allowed by nothing — it would produce a
     * connection that renders perfectly in the picker and answers nothing.
     */
    val canSave: Boolean
        get() = name.isNotBlank() &&
            (key.isNotBlank() || (!isNew && !needsKey)) &&
            (!provider.needsBaseUrl || AiBaseUrl.parse(baseUrl) != null) &&
            (!provider.needsModelIds || fastModel.isNotBlank())

    /** Whether the address, as typed, would send an API key unencrypted. */
    val cleartext: Boolean get() = AiBaseUrl.isCleartext(baseUrl)

    /** Whether the address, as typed, is not a URL at all — distinct from being empty. */
    val badBaseUrl: Boolean get() = baseUrl.isNotBlank() && AiBaseUrl.parse(baseUrl) == null

    internal fun modelFor(model: AiModel): String = when (model) {
        AiModel.FAST -> fastModel
        AiModel.BALANCED -> balancedModel
        AiModel.THOROUGH -> thoroughModel
    }
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
 *
 * The [AiModelCatalog] is here for the same shape of reason and a different need:
 * naming a model is choosing an identifier, and the app's standing rule is that an
 * identifier is chosen rather than typed wherever it can be.
 */
@Suppress("TooManyFunctions") // One member per thing the form does; the editor's fields set the count.
class AiConnectionsViewModel(
    private val repository: AiConnectionRepository,
    private val ai: Ai,
    private val catalog: AiModelCatalog,
    /**
     * For the status lines this holds — appContext.getString(R.string.ai_loading_models), the test result. They are
     * user-facing, so they come from resources; a ViewModel has no composition to read
     * them from, so it is handed the application context instead.
     */
    private val appContext: Context,
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
                AiConnections.hydrate(
                    connectionIds = connections.map { it.id },
                    configuredIds = connections.filter { it.isConfigured }.map { it.id },
                )
            }
        }
    }

    /** The connection with [id], for the picker field's display name. */
    fun connectionById(id: String): AiConnection? = repository.get(id)

    /** Whether [id]'s key is missing or unreadable — badged on the row. */
    fun needsKey(id: String): Boolean = repository.needsKey(id)

    fun addConnection() {
        val provider = AiProvider.GEMINI
        state.value = state.value.copy(
            draft = AiConnectionDraft(provider = provider, name = suggestedName(provider)),
        )
    }

    fun editConnection(id: String) {
        val connection = repository.get(id) ?: return
        state.value = state.value.copy(
            draft = AiConnectionDraft(
                id = connection.id,
                name = connection.name,
                provider = connection.provider,
                systemPrompt = connection.systemPrompt,
                baseUrl = connection.baseUrl,
                fastModel = connection.fastModel,
                balancedModel = connection.balancedModel,
                thoroughModel = connection.thoroughModel,
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

    fun onSystemPromptChange(value: String) = editDraft { it.copy(systemPrompt = value) }

    fun onBaseUrlChange(value: String) = editDraft { it.copy(baseUrl = value, message = "", failed = false) }

    fun onModelChange(model: AiModel, value: String) = editDraft { draft ->
        when (model) {
            AiModel.FAST -> draft.copy(fastModel = value)
            AiModel.BALANCED -> draft.copy(balancedModel = value)
            AiModel.THOROUGH -> draft.copy(thoroughModel = value)
        }
    }

    /**
     * Switches provider, carrying the suggested name along if it has not been typed
     * over.
     *
     * Following the name is worth the branch: somebody who opens the sheet, sees
     * "Gemini" filled in and then picks Claude has not chosen to call it "Gemini",
     * and a connection list where the Claude key is called Gemini is a small
     * permanent confusion nobody would deliberately create. A name the user actually
     * typed is never touched.
     *
     * The listing is cleared because it belongs to the old provider's server.
     */
    fun onProviderChange(value: AiProvider) = editDraft { draft ->
        val renamed = if (draft.name == suggestedName(draft.provider)) suggestedName(value) else draft.name
        draft.copy(provider = value, name = renamed, models = emptyList(), choosingFor = null)
    }

    /** Fills a base URL preset in; the host is a placeholder only the user can replace. */
    fun onPresetChosen(url: String) = editDraft { it.copy(baseUrl = url, message = "", failed = false) }

    /**
     * Asks the connection's own server which models its key may use.
     *
     * Reads through what is **stored** rather than what is in the form, exactly as
     * [test] does and for the same reason: the key is only ever in the repository,
     * and a listing that used an unsaved address would answer about a server the
     * macro will not be talking to.
     *
     * A failure is a message and nothing more. Several perfectly good servers do not
     * serve a listing at all, so the field stays editable and the name can be typed
     * — the chooser is a convenience over an open answer set, not a gate on it.
     */
    fun loadModels(model: AiModel) {
        val draft = state.value.draft ?: return
        if (draft.id.isBlank()) return
        editDraft { it.copy(busy = true, message = appContext.getString(R.string.ai_loading_models), failed = false) }
        viewModelScope.launch {
            val models = catalog.list(draft.id)
            editDraft {
                it.copy(
                    busy = false,
                    models = models.ids,
                    choosingFor = if (models.ids.isEmpty()) null else model,
                    message = models.error,
                    failed = models.error.isNotBlank(),
                )
            }
        }
    }

    fun closeModelChooser() = editDraft { it.copy(choosingFor = null) }

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
            val existing = if (draft.isNew) {
                repository.create(draft.name.trim(), draft.provider)
            } else {
                repository.get(draft.id) ?: return@launch
            }
            val connection = repository.upsert(draft.applyTo(existing))
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
                        message = appContext.getString(R.string.ai_key_not_stored_securely),
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
        editDraft { it.copy(busy = true, message = appContext.getString(R.string.ai_asking_the_model), failed = false) }
        viewModelScope.launch {
            val reply = ai.complete(
                AiRequest(connectionId = draft.id, prompt = TEST_PROMPT, maxOutputTokens = TEST_MAX_TOKENS),
            )
            editDraft {
                it.copy(
                    busy = false,
                    message = if (reply.error.isBlank()) {
                        appContext.getString(R.string.ai_model_answered, reply.text.trim())
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

    /** "Claude", then "Claude 2" and so on — a name the user can accept without typing. */
    private fun suggestedName(provider: AiProvider): String {
        val base = appContext.getString(provider.defaultNameRes())
        val taken = repository.list().map { it.name }.toSet()
        if (base !in taken) return base
        return generateSequence(2) { it + 1 }.first { "$base $it" !in taken }.let { "$base $it" }
    }

    companion object {
        private const val TEST_PROMPT = "Reply with the single word: working"

        /** Enough for the one word asked for, and nothing near enough for a paragraph. */
        private const val TEST_MAX_TOKENS = 32

        fun factory(
            repository: AiConnectionRepository,
            ai: Ai,
            catalog: AiModelCatalog,
            appContext: Context,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { AiConnectionsViewModel(repository, ai, catalog, appContext) }
        }
    }
}

/**
 * The draft's fields onto [connection], leaving the sealed key alone.
 *
 * Separate from the repository call so the mapping is one place rather than split
 * across the create and update branches — the bug that shape invites is a field
 * saved on edit and silently dropped on create.
 *
 * The base URL is stored **parsed**, so what a macro sends to and what the editor
 * showed cannot drift, and a pasted `…/chat/completions` is tidied once here rather
 * than on every request.
 */
private fun AiConnectionDraft.applyTo(connection: AiConnection): AiConnection = connection.copy(
    name = name.trim(),
    provider = provider,
    systemPrompt = systemPrompt.trim(),
    baseUrl = AiBaseUrl.parse(baseUrl).orEmpty(),
    fastModel = fastModel.trim(),
    balancedModel = balancedModel.trim(),
    thoroughModel = thoroughModel.trim(),
)
