package com.example.ottomatic.feature.nfc

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.ottomatic.data.NfcTagRepository
import com.example.ottomatic.domain.model.NfcTag
import com.example.ottomatic.domain.model.NfcTagId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * A tag being named — one just held to the phone, or a saved one being renamed.
 *
 * One type for both, because they differ only in where the [uid] came from. A
 * blank [uid] is the state that has no equivalent in any other editor in the app:
 * **the overlay is open and waiting for a tag to be tapped**. Nothing can be saved
 * until one arrives, which is what [canSave] says.
 */
data class NfcTagDraft(
    val uid: String = "",
    val name: String = "",
    val isNew: Boolean = true,
    /** NDEF content read at capture. Shown as a preview; never stored. */
    val text: String = "",
    /** The id will differ on the next tap, so saving it would achieve nothing. */
    val unstable: Boolean = false,
    /**
     * Whether this overlay is reading tags.
     *
     * Not derivable from [scanned], and that is the point: it stays true after the
     * first tap so a wrong tag can be corrected by simply tapping the right one.
     * A rename opened from the list never reads anything.
     */
    val capture: Boolean = true,
) {
    val scanned: Boolean get() = uid.isNotBlank()

    /** Saving an unstable id is allowed but pointless, so the button says so instead. */
    val canSave: Boolean get() = scanned && name.isNotBlank() && !unstable
}

/** What kind of NDEF record a write puts on a tag. */
enum class NfcWriteKind { TEXT, LINK }

/**
 * Content being written to a tag.
 *
 * [armed] is the moment reader mode goes on and the overlay starts waiting for the
 * tag to be held against the phone — the same shape as capture, and separate from
 * composing the content because a tag cannot usefully be held there while typing.
 */
data class NfcWriteDraft(
    val kind: NfcWriteKind = NfcWriteKind.TEXT,
    val content: String = "",
    val armed: Boolean = false,
    val outcome: String? = null,
    val failed: Boolean = false,
) {
    val canWrite: Boolean get() = content.isNotBlank()
}

data class NfcTagsUiState(
    val tags: List<NfcTag> = emptyList(),
    val draft: NfcTagDraft? = null,
    val write: NfcWriteDraft? = null,
)

/**
 * Drives the NFC tag library: the list, and the capture/rename overlay for one tag.
 *
 * Shared, exactly as [com.example.ottomatic.feature.geofence.GeofencePlacesViewModel]
 * is, by the standalone Tags screen and the picker a `trigger.nfc` config field
 * opens — see [LocalNfcTags] for how a config form several composables deep gets
 * hold of it.
 *
 * Unlike the geofence library it does **not** re-arm the engine after an edit, and
 * the difference is not an oversight. Arming a geofence registers a fence with the
 * platform at a particular spot, so moving a place has to tear that down and put it
 * back. Nothing about a tag is registered anywhere: the trigger matches on the uid
 * a tap carries and looks the name up through the repository at the moment it
 * fires, so a rename is visible to an already-armed macro immediately.
 */
@Suppress("TooManyFunctions") // One entry point per control across three overlays.
class NfcTagsViewModel(
    private val repository: NfcTagRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(NfcTagsUiState(tags = repository.list()))
    val uiState: StateFlow<NfcTagsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.tags.collect { tags -> _uiState.update { it.copy(tags = tags) } }
        }
    }

    fun tagByUid(uid: String): NfcTag? = repository.get(uid)

    /** Opens the overlay with nothing scanned yet, waiting for a tap. */
    fun scanNew() {
        _uiState.update { it.copy(draft = NfcTagDraft()) }
    }

    fun editTag(uid: String) {
        val tag = repository.get(uid) ?: return
        _uiState.update {
            it.copy(draft = NfcTagDraft(uid = tag.uid, name = tag.name, isNew = false, capture = false))
        }
    }

    fun closeEditor() {
        _uiState.update { it.copy(draft = null) }
    }

    fun draftNameChanged(name: String) {
        _uiState.update { state -> state.copy(draft = state.draft?.copy(name = name)) }
    }

    /**
     * A tag arrived while the capture overlay was open.
     *
     * Called from the reader-mode callback, which runs on a binder thread — going
     * through the ViewModel is what marshals it, since [MutableStateFlow] is safe
     * to write from anywhere and Compose reads the snapshot on its own thread.
     *
     * A tag already in the library keeps its name rather than presenting a blank
     * field: re-scanning something you have already saved is far more often "which
     * one is this?" than "let me rename it".
     */
    fun tagScanned(uid: String, techs: List<String>, text: String) {
        _uiState.update { state ->
            val draft = state.draft ?: return@update state
            val existing = repository.get(uid)
            state.copy(
                draft = draft.copy(
                    uid = uid,
                    name = draft.name.ifBlank { existing?.name.orEmpty() },
                    isNew = existing == null,
                    text = text,
                    // Judged here from what the platform reported rather than
                    // passed in, so the one rule about which ids are worth saving
                    // stays in `NfcTagId` and every caller gets the same answer.
                    unstable = NfcTagId.isUnstable(uid, techs),
                ),
            )
        }
    }

    /** Saves the open draft. [onSaved] receives the uid, so a picker can select it. */
    fun save(onSaved: (String) -> Unit = {}) {
        val draft = _uiState.value.draft?.takeIf { it.canSave } ?: return
        viewModelScope.launch {
            val existing = repository.get(draft.uid)
            repository.upsert(
                NfcTag(
                    uid = draft.uid,
                    name = draft.name.trim(),
                    addedAtEpochMs = existing?.addedAtEpochMs ?: System.currentTimeMillis(),
                ),
            )
            closeEditor()
            onSaved(draft.uid)
        }
    }

    // ---- Writing -----------------------------------------------------------

    fun startWrite() {
        _uiState.update { it.copy(write = NfcWriteDraft()) }
    }

    fun closeWrite() {
        _uiState.update { it.copy(write = null) }
    }

    fun writeKindChanged(kind: NfcWriteKind) {
        _uiState.update { state -> state.copy(write = state.write?.copy(kind = kind, outcome = null)) }
    }

    fun writeContentChanged(content: String) {
        _uiState.update { state -> state.copy(write = state.write?.copy(content = content, outcome = null)) }
    }

    /** Turns reader mode on and starts waiting for the tag to be presented. */
    fun armWrite() {
        _uiState.update { state ->
            val write = state.write ?: return@update state
            if (!write.canWrite) return@update state
            state.copy(write = write.copy(armed = true, outcome = null))
        }
    }

    /**
     * The result of one write attempt.
     *
     * Reported back rather than performed here: a write needs an `android.nfc.Tag`,
     * which is only valid on the binder thread it arrived on and only while the tag
     * is still in the field. Keeping it out of this class is what stops somebody
     * later stashing one in state and using it a second too late.
     */
    fun writeFinished(result: Result<Unit>) {
        _uiState.update { state ->
            val write = state.write ?: return@update state
            state.copy(
                write = write.copy(
                    armed = false,
                    failed = result.isFailure,
                    outcome = result.fold(
                        onSuccess = { "Written." },
                        onFailure = { it.message ?: "Could not write to this tag." },
                    ),
                ),
            )
        }
    }

    fun delete(uid: String) {
        viewModelScope.launch {
            repository.delete(uid)
            if (_uiState.value.draft?.uid == uid) closeEditor()
        }
    }

    companion object {
        fun factory(repository: NfcTagRepository): ViewModelProvider.Factory = viewModelFactory {
            initializer { NfcTagsViewModel(repository) }
        }
    }
}
