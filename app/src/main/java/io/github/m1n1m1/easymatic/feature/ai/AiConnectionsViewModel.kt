package io.github.m1n1m1.easymatic.feature.ai

import android.content.Context
import io.github.m1n1m1.easymatic.R
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.m1n1m1.easymatic.core.service.Ai
import io.github.m1n1m1.easymatic.core.service.AiModel
import io.github.m1n1m1.easymatic.core.service.AiRequest
import io.github.m1n1m1.easymatic.core.service.CallableMacro
import io.github.m1n1m1.easymatic.core.service.MacroControl
import io.github.m1n1m1.easymatic.data.AiConnectionRepository
import io.github.m1n1m1.easymatic.data.ai.AiModelCatalog
import io.github.m1n1m1.easymatic.data.ai.OnDeviceDownload
import io.github.m1n1m1.easymatic.data.ai.OnDeviceSetup
import io.github.m1n1m1.easymatic.data.ai.OnDeviceStatus
import io.github.m1n1m1.easymatic.data.ai.AiModelInfo
import io.github.m1n1m1.easymatic.domain.model.AiBaseUrl
import io.github.m1n1m1.easymatic.domain.model.AiConnection
import io.github.m1n1m1.easymatic.domain.model.AiModelProfile
import io.github.m1n1m1.easymatic.domain.model.isOnDevice
import io.github.m1n1m1.easymatic.domain.model.needsKey
import io.github.m1n1m1.easymatic.domain.model.AiModality
import io.github.m1n1m1.easymatic.domain.model.AiProvider
import io.github.m1n1m1.easymatic.domain.model.needsBaseUrl
import io.github.m1n1m1.easymatic.domain.model.needsModelIds
import io.github.m1n1m1.easymatic.domain.registry.AiConnections
import java.util.UUID
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
    val baseUrl: String = "",
    /** This account's ways of asking — see [AiModelProfileDraft]. */
    val models: List<AiModelProfileDraft> = emptyList(),
    val isNew: Boolean = true,
    val needsKey: Boolean = false,
    val busy: Boolean = false,
    val message: String = "",
    val failed: Boolean = false,
    /**
     * The listing from this connection's own server, once somebody has asked for it.
     *
     * Named apart from [models], which is the profiles the user has minted. The two are
     * easy to confuse and mean opposite things: that is what this account *offers*, this
     * is what the user has *chosen to keep*.
     */
    val listedModels: List<AiModelInfo> = emptyList(),
    /**
     * Which input kinds the chooser is narrowed to, or empty for all of them.
     *
     * **A row whose modalities are null is never hidden by this**, whatever is ticked.
     * Only OpenRouter publishes what each model accepts; treating the other four
     * providers' silence as "does not" would empty the list on every one of them. The
     * filter narrows what is known and leaves what is not, and the screen says so.
     */
    val modalityFilter: Set<AiModality> = emptySet(),
    /** Which profile's sub-editor is open, by id, or null when none is. */
    val editingModel: String? = null,
    /** Whether the id chooser is open over the profile being edited. */
    val choosingModelId: Boolean = false,
    /**
     * What this phone can do about the on-device model, or null while it is being asked.
     *
     * **Null is "not asked yet" and not "no"**, which is `CapabilityStatus.UNKNOWN`'s rule
     * in a third place: the question is an inter-process round trip, and a screen that
     * said "this phone cannot run it" for the frame before the answer arrived would be
     * telling somebody with a Pixel 11 to go and configure a fallback.
     */
    val onDeviceStatus: OnDeviceStatus? = null,
    /** What the phone calls its own model, once it has said. Blank until then. */
    val baseModelName: String = "",
    /** Percentage complete while a download runs, or null when none is. */
    val downloadPercent: Int? = null,
) {
    /**
     * A new connection needs a name and a key; an existing one needs only a name,
     * unless its stored key has become unreadable — the restored-phone case, where
     * saving without one would leave it just as broken as it was.
     *
     * The address requirement is read off the same rule the Problems panel and the
     * facade use rather than restated here: a self-hosted connection needs somewhere
     * to send requests. Saving without it is allowed by nothing — it would produce a
     * connection that renders perfectly in the picker and answers nothing.
     *
     * **Every model has to be complete too**, and that gate lives here rather than on
     * a Save button of its own inside the sub-editor: the profiles are part of this
     * draft and commit with it, so one incomplete row would otherwise be written
     * alongside four good ones and only ever surface as a node that quietly stops
     * answering. The row says which one it is.
     */
    val canSave: Boolean
        get() = name.isNotBlank() &&
            (!provider.needsKey || key.isNotBlank() || (!isNew && !needsKey)) &&
            (!provider.needsBaseUrl || AiBaseUrl.parse(baseUrl) != null) &&
            models.all { it.isComplete(provider) }

    /** Whether the address, as typed, would send an API key unencrypted. */
    val cleartext: Boolean get() = AiBaseUrl.isCleartext(baseUrl)

    /** Whether the address, as typed, is not a URL at all — distinct from being empty. */
    val badBaseUrl: Boolean get() = baseUrl.isNotBlank() && AiBaseUrl.parse(baseUrl) == null

    /** The profile the sub-editor is open over, if any. */
    val openModel: AiModelProfileDraft? get() = models.firstOrNull { it.id == editingModel }
}

/**
 * One model profile being edited, inside its connection's draft.
 *
 * Nested rather than a draft of its own with a Save button, because a profile has no
 * existence apart from the account that answers it: the id is minted here and only
 * reaches storage when the connection is saved, so abandoning the form abandons the
 * profile too — which is what somebody who backed out of adding one means.
 */
data class AiModelProfileDraft(
    val id: String,
    val name: String = "",
    val modelId: String = "",
    val effort: AiModel = AiModel.FAST,
    val systemPrompt: String = "",
    /** A `ToolSpec` list, edited by the tool permission screen and stored verbatim. */
    val tools: String = "",
    /**
     * How long a reply may be, as typed. Text rather than an `Int` because a field being
     * emptied to retype it is not the user asking for zero.
     */
    val maxOutputTokens: String = AiModelProfile.DEFAULT_MAX_OUTPUT_TOKENS.toString(),
    /** Another profile's id to ask when this phone cannot run this one. Blank for none. */
    val fallbackModelRef: String = "",
) {
    /** Whether this row would answer anything — a name to pick it by, and a model to ask. */
    fun isComplete(provider: AiProvider): Boolean =
        name.isNotBlank() && (!provider.needsModelIds || modelId.isNotBlank())
}

data class AiConnectionsUiState(
    val connections: List<AiConnection> = emptyList(),
    val draft: AiConnectionDraft? = null,
    /**
     * The macros a model may be allowed to run — those carrying a `trigger.api` node.
     *
     * Held on the state rather than read where it is drawn because it is a file read
     * per macro: the permission list has to be able to say "no macros can be called
     * from outside" without having gone and looked while the screen was composing.
     */
    val callableMacros: List<CallableMacro> = emptyList(),
)

/**
 * Drives the AI connection library.
 *
 * **Activity-scoped**, unlike the single-key screen it replaces, and for
 * [io.github.m1n1m1.easymatic.feature.mail.MailAccountsViewModel]'s exact reason: a
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
     * The on-device model's status and download, for the one provider that has them.
     *
     * Beside [catalog] rather than folded into it for the same reason [catalog] is beside
     * [ai]: these are the *editor's* questions. Nothing a macro does can reach either.
     */
    private val onDevice: OnDeviceSetup,
    /** Lists the macros a model profile may be allowed to run; null in tests and previews. */
    private val macroControl: MacroControl? = null,
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
                AiConnections.hydrateFrom(connections)
            }
        }
    }

    /** The connection with [id], for the picker field's display name. */
    fun connectionById(id: String): AiConnection? = repository.get(id)

    /** The connection holding the profile with [profileId], for the model picker's rows. */
    fun connectionForProfile(profileId: String): AiConnection? = repository.connectionForProfile(profileId)

    /** The profile with [profileId], for the picker field's display name. */
    fun modelProfile(profileId: String): AiModelProfile? = repository.resolve(profileId)?.second

    /**
     * Whether [id]'s key is missing or unreadable — badged on the row.
     *
     * A provider that needs no key never needs one pasting in again, however empty its
     * sealed secret is. Without this the on-device connection would be badged "the key
     * could not be read" on the list, about a key it has never had.
     */
    fun needsKey(id: String): Boolean =
        repository.get(id)?.provider?.needsKey == true && repository.needsKey(id)

    /**
     * A new connection starts with **one** model rather than none.
     *
     * An empty list is technically the honest starting state and a bad one to present:
     * a connection with no model answers nothing, and the form would give no hint that
     * anything more was needed. One row named after the tier it uses is a working
     * connection the moment the key is pasted, and deleting it is one tap for the
     * minority who want something else.
     */
    fun addConnection() {
        val provider = AiProvider.GEMINI
        state.value = state.value.copy(
            draft = AiConnectionDraft(
                provider = provider,
                name = suggestedName(provider),
                models = listOf(newModelDraft(AiModel.FAST)),
            ),
        )
    }

    /** Appends a model to the draft and opens its sub-editor on it. */
    fun addModel() {
        val added = newModelDraft(nextEffort(state.value.draft?.models.orEmpty()))
        editDraft { it.copy(models = it.models + added) }
        editModel(added.id)
    }

    /**
     * Opens a model's sub-editor, and reads the callable-macro list while it is open.
     *
     * Read here rather than in `init` because it is a file read per macro and most
     * visits to this screen never open the permission list at all — and re-read on
     * every open, because a macro may have gained a `trigger.api` node since last time.
     */
    fun editModel(profileId: String) {
        editDraft { it.copy(editingModel = profileId, choosingModelId = false) }
        loadCallableMacros()
    }

    /**
     * Reads the macros a model may be allowed to run.
     *
     * Its own member because two screens open the permission list — a profile's editor
     * and an Ask AI node's — and both need it read at the moment the list opens rather
     * than at construction: it is a file read per macro, most visits never open the
     * list at all, and a macro may have gained a `trigger.api` node since last time.
     */
    fun loadCallableMacros() {
        viewModelScope.launch {
            state.value = state.value.copy(callableMacros = macroControl?.callable().orEmpty())
        }
    }

    fun closeModelEditor() = editDraft { it.copy(editingModel = null, choosingModelId = false) }

    fun deleteModel(profileId: String) = editDraft { draft ->
        draft.copy(
            models = draft.models.filterNot { it.id == profileId },
            editingModel = draft.editingModel.takeIf { it != profileId },
        )
    }

    /**
     * Changes one field of one model.
     *
     * A single transform rather than a setter per field, which is the one place this
     * screen departs from the flat `onXChange` shape around it: a profile has six
     * fields and will grow more, and six near-identical three-line methods is how one
     * of them ends up copying the wrong branch.
     */
    fun updateModel(profileId: String, transform: (AiModelProfileDraft) -> AiModelProfileDraft) = editDraft { draft ->
        draft.copy(models = draft.models.map { if (it.id == profileId) transform(it) else it })
    }

    fun editConnection(id: String) {
        val connection = repository.get(id) ?: return
        state.value = state.value.copy(
            draft = AiConnectionDraft(
                id = connection.id,
                name = connection.name,
                provider = connection.provider,
                baseUrl = connection.baseUrl,
                models = connection.models.map { profile ->
                    AiModelProfileDraft(
                        id = profile.id,
                        name = profile.name,
                        modelId = profile.modelId,
                        effort = profile.effort,
                        systemPrompt = profile.systemPrompt,
                        tools = profile.tools,
                        maxOutputTokens = profile.maxOutputTokens.toString(),
                        fallbackModelRef = profile.fallbackModelRef,
                    )
                },
                isNew = false,
                needsKey = needsKey(connection.id),
            ),
        )
        if (connection.provider.isOnDevice) refreshOnDevice()
    }

    /**
     * Asks the phone what it can do about the on-device model, and what it calls it.
     *
     * **Re-asked on every open rather than held**, because every one of the four answers
     * can change while this screen is closed: a download finishes, or somebody comes back
     * from a system update. The engine caches the two answers that genuinely cannot
     * change, so asking again is free where it is free and correct where it is not.
     *
     * The name is read only when the model is actually there — a phone that cannot run it
     * has no name to give, and asking would be a round trip for a blank.
     */
    private fun refreshOnDevice() {
        viewModelScope.launch {
            val status = onDevice.status()
            val name = if (status == OnDeviceStatus.AVAILABLE) onDevice.modelName() else ""
            editDraft { it.copy(onDeviceStatus = status, baseModelName = name) }
        }
    }

    /**
     * Downloads the model weights, reporting progress into the draft.
     *
     * **The one place in the app that starts this**, and deliberately so: it is large
     * enough to be a metered-data decision, so it belongs to a button somebody pressed
     * rather than to a macro that fired at three in the morning. A macro meeting an
     * undownloaded model takes its fallback and says so in the run log.
     *
     * A percentage needs a total, and the device does not always give one — a download
     * with no total reports progress but no percentage, which the screen renders as an
     * indeterminate bar rather than as a confident zero.
     */
    fun download() {
        viewModelScope.launch {
            editDraft { it.copy(downloadPercent = 0, message = "", failed = false) }
            onDevice.download().collect { progress ->
                when (progress) {
                    is OnDeviceDownload.Started ->
                        editDraft { it.copy(downloadPercent = percentOf(0, progress.totalBytes)) }

                    is OnDeviceDownload.Progress -> editDraft {
                        it.copy(downloadPercent = percentOf(progress.downloadedBytes, progress.totalBytes))
                    }

                    OnDeviceDownload.Done -> {
                        editDraft { it.copy(downloadPercent = null) }
                        refreshOnDevice()
                    }

                    is OnDeviceDownload.Failed -> editDraft {
                        it.copy(downloadPercent = null, message = progress.error, failed = true)
                    }
                }
            }
        }
    }

    /** Sets a profile's fallback, or clears it when [profileId] is blank. */
    fun onFallbackChosen(profileId: String) {
        val editing = state.value.draft?.editingModel ?: return
        updateModel(editing) { it.copy(fallbackModelRef = profileId) }
    }

    private fun percentOf(done: Long, total: Long): Int? =
        if (total <= 0L) null else ((done * PERCENT) / total).toInt().coerceIn(0, PERCENT.toInt())

    fun closeEditor() {
        state.value = state.value.copy(draft = null)
    }

    fun onNameChange(value: String) = editDraft { it.copy(name = value, message = "", failed = false) }

    fun onKeyChange(value: String) = editDraft { it.copy(key = value, message = "", failed = false) }

    fun onBaseUrlChange(value: String) = editDraft { it.copy(baseUrl = value, message = "", failed = false) }

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
        draft.copy(
            provider = value,
            name = renamed,
            listedModels = emptyList(),
            modalityFilter = emptySet(),
            choosingModelId = false,
            // Cleared rather than kept: the answer belongs to the provider that was
            // selected, and a stale "ready" beside a Gemini key would be a lie.
            onDeviceStatus = null,
            baseModelName = "",
        )
    }.also { if (value.isOnDevice) refreshOnDevice() }

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
    fun loadModels() {
        val draft = state.value.draft ?: return
        if (draft.id.isBlank()) return
        editDraft { it.copy(busy = true, message = appContext.getString(R.string.ai_loading_models), failed = false) }
        viewModelScope.launch {
            val models = catalog.list(draft.id)
            editDraft {
                it.copy(
                    busy = false,
                    listedModels = models.models,
                    modalityFilter = emptySet(),
                    choosingModelId = models.models.isNotEmpty(),
                    message = models.error,
                    failed = models.error.isNotBlank(),
                )
            }
        }
    }

    fun closeModelChooser() = editDraft { it.copy(choosingModelId = false) }

    /** Ticks one input kind on or off in the chooser's filter. */
    fun toggleModality(modality: AiModality) = editDraft { draft ->
        val next = if (modality in draft.modalityFilter) {
            draft.modalityFilter - modality
        } else {
            draft.modalityFilter + modality
        }
        draft.copy(modalityFilter = next)
    }

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
                // The **first profile's** id and not the connection's: a node stores a
                // profile id, and the only caller is a picker that wants to select what
                // was just created. Blank when the connection has no model yet, which
                // the picker reads as "nothing to select".
                onSaved(connection.models.firstOrNull()?.id.orEmpty())
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
    @Suppress("ReturnCount") // Nothing to test, nothing saved, no model — three distinct refusals.
    fun test() {
        val draft = state.value.draft ?: return
        if (draft.id.isBlank()) return
        // Through the **stored** first model, for the reason the test exists at all: a
        // request built from the form would prove a route no macro will ever take.
        val modelRef = repository.get(draft.id)?.models?.firstOrNull()?.id
        if (modelRef == null) {
            editDraft { it.copy(message = appContext.getString(R.string.ai_no_model_to_test), failed = true) }
            return
        }
        editDraft { it.copy(busy = true, message = appContext.getString(R.string.ai_asking_the_model), failed = false) }
        viewModelScope.launch {
            val reply = ai.complete(
                AiRequest(modelRef = modelRef, prompt = TEST_PROMPT, maxOutputTokens = TEST_MAX_TOKENS),
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

    /**
     * A fresh profile, named after the tier it starts on.
     *
     * The id is minted here and is a UUID, unlike the deterministic ones the migration
     * writes — nothing has to derive this one from anything, and a UUID cannot collide
     * with a legacy id or with a second profile added in the same breath.
     */
    private fun newModelDraft(effort: AiModel) = AiModelProfileDraft(
        id = UUID.randomUUID().toString(),
        name = appContext.getString(effort.labelRes()),
        effort = effort,
    )

    /** The first tier this connection has no profile on, so adding twice gives two different rows. */
    private fun nextEffort(existing: List<AiModelProfileDraft>): AiModel =
        AiModel.entries.firstOrNull { effort -> existing.none { it.effort == effort } } ?: AiModel.FAST

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

        private const val PERCENT = 100L

        @Suppress("LongParameterList") // Four collaborators and a context; a holder would rename them.
        fun factory(
            repository: AiConnectionRepository,
            ai: Ai,
            catalog: AiModelCatalog,
            onDevice: OnDeviceSetup,
            appContext: Context,
            macroControl: MacroControl? = null,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                AiConnectionsViewModel(repository, ai, catalog, onDevice, macroControl, appContext)
            }
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
    baseUrl = AiBaseUrl.parse(baseUrl).orEmpty(),
    models = models.map { profile ->
        AiModelProfile(
            id = profile.id,
            name = profile.name.trim(),
            modelId = profile.modelId.trim(),
            effort = profile.effort,
            systemPrompt = profile.systemPrompt.trim(),
            tools = profile.tools,
            // A blank or nonsense figure is the profile saying nothing rather than saying
            // zero, and `replyLimit` reads that as "fall back" — so it round-trips to the
            // built-in default instead of to a model that may answer nothing at all.
            maxOutputTokens = profile.maxOutputTokens.trim().toIntOrNull()?.takeIf { it > 0 }
                ?: AiModelProfile.DEFAULT_MAX_OUTPUT_TOKENS,
            fallbackModelRef = profile.fallbackModelRef,
        )
    },
)
