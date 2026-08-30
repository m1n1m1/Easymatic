package io.github.m1n1m1.easymatic.feature.translate

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.m1n1m1.easymatic.data.translate.TranslationSetup
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Which languages this phone can translate, and adding or removing one.
 *
 * **There is no repository behind this**, on `FolderAccessViewModel`'s reasoning and for its reason
 * exactly: ML Kit's own list of downloaded models *is* the list. A JSON file beside it would be a
 * second, worse answer — it would survive a Clear Storage that the models themselves do not, and
 * would then describe megabytes that are not there, rendering perfectly and freeing nothing.
 *
 * **It is activity-scoped and shared with the editor's language picker**, which is what makes the
 * two agree: a language downloaded here appears in the node's chooser without anything having to
 * invalidate anything, because [languages] is the same flow both are reading. `TranslateLanguages`
 * is republished underneath as well, for `PickerOptions` and the AI tool harness, which have no
 * composition to observe from.
 */
class TranslationModelsViewModel(private val setup: TranslationSetup) : ViewModel() {

    private val _languages = MutableStateFlow<List<String>>(emptyList())

    /** The languages whose models are downloaded, as BCP-47 tags, sorted. */
    val languages: StateFlow<List<String>> = _languages.asStateFlow()

    private val _busy = MutableStateFlow(true)

    /**
     * Whether a read, a download or a delete is in flight.
     *
     * Starts **true**, which is the whole reason it exists: the first list arrives one IPC after the
     * screen does, and an empty list drawn in the meantime says "you have downloaded nothing" to
     * somebody who has downloaded plenty.
     */
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _working = MutableStateFlow("")

    /**
     * The language currently being downloaded, or blank.
     *
     * Separate from [busy] because the screen says something different about it: a download is tens
     * of megabytes and takes real time, so the row has to name what it is waiting for. ML Kit
     * reports no *progress* for a model download — it is one `Task` that completes or fails — so a
     * name and a spinner is the whole of what can honestly be shown.
     */
    val working: StateFlow<String> = _working.asStateFlow()

    private val _error = MutableStateFlow("")

    /** The last download's or delete's complaint, or blank. */
    val error: StateFlow<String> = _error.asStateFlow()

    /**
     * The languages that could be added — everything supported, minus what is already here.
     *
     * Computed rather than stored, because it is a function of two things that both change.
     */
    fun addable(): List<String> = setup.supported() - _languages.value.toSet()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _busy.value = true
            _languages.value = setup.installed()
            _busy.value = false
        }
    }

    /**
     * Downloads one language's model and re-reads the list.
     *
     * Re-reads rather than adding the row locally, on [delete]'s reasoning: the answer that matters
     * is what is on the phone, and only the list can be believed.
     */
    fun download(language: String) {
        viewModelScope.launch {
            _busy.value = true
            _working.value = language
            _error.value = setup.download(language)
            _working.value = ""
            _languages.value = setup.installed()
            _busy.value = false
        }
    }

    /**
     * Deletes one language's model and re-reads the list.
     *
     * Re-reads rather than removing the row locally, because a delete that failed must not leave the
     * screen claiming it worked — and `TranslationSetup.delete` reports a refusal as a sentence
     * rather than an exception, so the list is the only thing that can be believed.
     */
    fun delete(language: String) {
        viewModelScope.launch {
            _busy.value = true
            _error.value = setup.delete(language)
            _languages.value = setup.installed()
            _busy.value = false
        }
    }

    /** Dismisses the last complaint. */
    fun clearError() {
        _error.value = ""
    }

    class Factory(private val setup: TranslationSetup) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            TranslationModelsViewModel(setup) as T
    }
}
