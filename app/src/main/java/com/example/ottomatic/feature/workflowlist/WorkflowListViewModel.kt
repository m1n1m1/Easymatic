package com.example.ottomatic.feature.workflowlist

import com.example.ottomatic.R
import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.ottomatic.core.service.RunLog
import com.example.ottomatic.data.MacroTransferRepository
import com.example.ottomatic.data.WorkflowRepository
import com.example.ottomatic.data.trigger.VariableStore
import com.example.ottomatic.domain.model.MacroAccent
import com.example.ottomatic.domain.model.MacroIcon
import com.example.ottomatic.domain.model.WorkflowSummary
import com.example.ottomatic.domain.model.reissuedConfig
import com.example.ottomatic.domain.transfer.ImportResult
import com.example.ottomatic.engine.service.MacroEngineService
import com.example.ottomatic.engine.validation.GraphValidator
import com.example.ottomatic.feature.widget.MacroSnapshots
import com.example.ottomatic.feature.widget.ManualTriggerRef
import com.example.ottomatic.feature.widget.RunTilePin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class WorkflowListUiState(
    val workflows: List<WorkflowSummary> = emptyList(),
    /**
     * Error count per workflow id, for the ids that have any.
     *
     * A macro that cannot run should say so from here rather than only once the
     * editor is open: an armed macro with a broken wire looks exactly like a
     * working one from this screen, which is where the user goes to ask "is it on?".
     */
    val errors: Map<String, Int> = emptyMap(),
    /**
     * Each workflow's manual triggers, for the rows that have any.
     *
     * Here because "Add to home screen" has to know whether there is anything to
     * pin *before* it is offered, and because a macro with several manual triggers
     * has to be asked which one.
     */
    val triggers: Map<String, List<ManualTriggerRef>> = emptyMap(),
    val isLoading: Boolean = true,
    /**
     * The outcome of the last export or import, or null when there is nothing to say.
     *
     * Held in the state rather than raised as a one-shot event because both outcomes
     * are things the user has to read and act on — an import lists what it adopted and
     * what still needs setting up — and a snackbar that vanishes mid-sentence is not a
     * place for that. Cleared by [dismissTransfer] when the dialog is closed.
     */
    val transfer: TransferMessage? = null,
)

/** What to tell the user about the export or import that just happened. */
sealed interface TransferMessage {

    /** The macro is in, under this name, with these two lists to report. */
    data class Imported(
        val name: String,
        val adopted: List<String>,
        val needs: List<String>,
        val needsPlugins: List<String>,
        val needsApps: List<String>,
    ) : TransferMessage

    /** It did not go in, for a reason worth naming. */
    data class Failed(val reason: TransferFailure) : TransferMessage
}

/** Why a transfer did not happen, kept separate from how it is worded. */
enum class TransferFailure { UNREADABLE, TOO_NEW, TOO_OLD, EXPORT_FAILED }

@Suppress("TooManyFunctions") // CRUD surface over the workflow collection.
class WorkflowListViewModel(
    private val repository: WorkflowRepository,
    private val transfers: MacroTransferRepository,
    private val runLog: RunLog,
    private val appContext: Context,
) : ViewModel() {

    private val _uiState = MutableStateFlow(WorkflowListUiState())
    val uiState: StateFlow<WorkflowListUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    /**
     * Reloads the persisted workflow summaries, their problem counts and their
     * manual triggers.
     *
     * All three come from [MacroSnapshots], which is the same pass this screen
     * used to make on its own — load every workflow, run [GraphValidator] over it —
     * now shared with the home-screen widgets, which need exactly the same three
     * things. One pass rather than two also means the list and the widgets cannot
     * disagree about how many problems a macro has.
     *
     * The cache is dropped first. It is invalidated asynchronously by
     * `WidgetUpdater` when the repository reports a write, and this method is
     * called *immediately* after a mutation — so without this it would race that
     * collector and could redraw the row that was just edited from the state it
     * was in before.
     */
    fun refresh() {
        viewModelScope.launch {
            MacroSnapshots.invalidate()
            val snapshots = MacroSnapshots.all()
            _uiState.update { state ->
                state.copy(
                    workflows = repository.list(),
                    errors = snapshots.filter { it.errorCount > 0 }.associate { it.workflowId to it.errorCount },
                    triggers = snapshots.filter { it.triggers.isNotEmpty() }
                        .associate { it.workflowId to it.triggers },
                    isLoading = false,
                )
            }
        }
    }

    /** Creates a new empty workflow and returns its id (for navigation). */
    fun create(onCreated: (String) -> Unit) {
        viewModelScope.launch {
            val workflow = repository.create(appContext.getString(R.string.workflowlist_new_workflow))
            refresh()
            onCreated(workflow.id)
        }
    }

    fun delete(id: String) {
        // Disarm first so the engine releases trigger sources for this workflow
        // before its persisted file disappears.
        MacroEngineService.start(appContext, MacroEngineService.ACTION_DISABLE, id)
        // Its console goes with it. A workflow id is reused only if the user
        // recreates one by hand, but a new macro inheriting a deleted one's
        // errors would be baffling, and the file would otherwise never be freed.
        runLog.clear(id)
        // And so does what it remembered, for the same reason: its declarations
        // are in the file about to be deleted, so their values would sit in
        // variables.json under a workflow id nothing will ever look up again.
        VariableStore.clearScope(id)
        viewModelScope.launch {
            repository.delete(id)
            refresh()
        }
    }

    /**
     * Writes [id] to [target] as an export file.
     *
     * The file is produced by [MacroTransferRepository], which strips the one
     * credential a graph can hold and gathers the credential-free library entries the
     * macro points at, so what lands on the other phone resolves rather than dangling.
     */
    fun export(id: String, target: Uri) {
        viewModelScope.launch {
            val text = transfers.exportText(id)
            val written = text != null && write(target, text)
            if (!written) fail(TransferFailure.EXPORT_FAILED)
        }
    }

    /**
     * Writes [id] into the cache and hands the share sheet a read grant on it.
     *
     * A cache copy rather than the stored file: the stored one holds the live API token
     * and the arming flag, and it lives beside every other macro in a directory no
     * other app may be given a foothold in. See `res/xml/file_paths.xml`.
     */
    fun share(id: String, name: String) {
        viewModelScope.launch {
            val text = transfers.exportText(id)
            if (text == null || !MacroSharing.share(appContext, transfers.fileNameFor(name), text)) {
                fail(TransferFailure.EXPORT_FAILED)
            }
        }
    }

    /**
     * The filename to offer the document picker for a macro called [name].
     *
     * Delegated rather than reimplemented here: the sanitising and the `.otto.json`
     * suffix are the export format's business, and a second copy of them in the UI
     * would be free to drift from the one the share path uses.
     */
    fun suggestedFileName(name: String): String = transfers.fileNameFor(name)

    /** Reads [source] and, when it holds a macro, saves it under a fresh id. */
    fun import(source: Uri) {
        viewModelScope.launch {
            val text = read(source)
            val result = if (text == null) ImportResult.Unreadable else transfers.import(text)
            when (result) {
                is ImportResult.Ready -> {
                    refresh()
                    _uiState.update { it.copy(transfer = imported(result)) }
                }
                is ImportResult.TooNew -> fail(TransferFailure.TOO_NEW)
                is ImportResult.TooOld -> fail(TransferFailure.TOO_OLD)
                ImportResult.Unreadable -> fail(TransferFailure.UNREADABLE)
            }
        }
    }

    /**
     * Copies [id] into a new macro, disarmed and with its credentials re-minted.
     *
     * Goes through the same [reissuedConfig] the editor's duplicate-selection uses, on
     * the same argument: a node must not work or not depending on how it was made, and
     * a copy sharing the original's API token would leave two endpoints behind one
     * secret.
     */
    fun duplicate(id: String) {
        viewModelScope.launch {
            val original = repository.load(id) ?: return@launch
            val copy = repository.create(
                appContext.getString(R.string.macro_transfer_copy_suffix, original.name),
            )
            repository.save(
                original.copy(
                    id = copy.id,
                    name = copy.name,
                    enabled = false,
                    nodes = original.nodes.map { it.copy(config = reissuedConfig(it)) },
                ),
            )
            refresh()
        }
    }

    /** Clears the export/import message once its dialog has been read. */
    fun dismissTransfer() {
        _uiState.update { it.copy(transfer = null) }
    }

    private fun imported(result: ImportResult.Ready): TransferMessage.Imported {
        val bundled = result.export.bundled
        return TransferMessage.Imported(
            name = result.workflow.name,
            adopted = bundled.globals.map { it.name } +
                bundled.places.map { it.name } +
                bundled.nfcTags.map { it.name },
            needs = result.export.requires.setup,
            needsPlugins = result.export.requires.plugins,
            needsApps = result.export.requires.apps,
        )
    }

    private fun fail(reason: TransferFailure) {
        _uiState.update { it.copy(transfer = TransferMessage.Failed(reason)) }
    }

    private suspend fun write(target: Uri, text: String): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                // "wt" truncates. Without it, overwriting a longer file leaves the tail
                // of the old one past the end of the new JSON, which parses as garbage
                // on the way back in rather than failing at the point of writing.
                appContext.contentResolver.openOutputStream(target, "wt")?.use {
                    it.write(text.toByteArray())
                } != null
            }.getOrDefault(false)
        }

    private suspend fun read(source: Uri): String? = withContext(Dispatchers.IO) {
        runCatching {
            appContext.contentResolver.openInputStream(source)?.use {
                it.readBytes().decodeToString()
            }
        }.getOrNull()
    }

    /**
     * Asks the launcher to place a Run tile widget for [trigger] on the home screen.
     *
     * A widget rather than the launcher shortcut this used to pin: the two occupy the
     * same grid cell and run the same macro, but a shortcut is a static icon the
     * launcher owns, while a tile reports what the run is doing. See [RunTilePin].
     *
     * Returns false when the launcher refuses — several launchers do not support
     * pinning at all — so the screen can say so rather than leaving the user
     * waiting for a system dialog that is never going to appear.
     */
    fun pin(trigger: ManualTriggerRef): Boolean = RunTilePin.request(appContext, trigger)

    /** Applies everything the Edit dialog can change: name, icon and accent. */
    fun updateMacro(id: String, name: String, icon: MacroIcon, accent: MacroAccent) {
        viewModelScope.launch {
            repository.updateMacro(id, name, icon, accent)
            refresh()
        }
    }

    /**
     * Persists the armed flag for [id] and starts/stops the background engine
     * service accordingly. Mirrors [GraphEditorViewModel.setMacroEnabled]. After
     * persisting, the list is refreshed so the [Switch] reflects the new state.
     */
    fun setEnabled(id: String, enabled: Boolean) {
        viewModelScope.launch {
            repository.setEnabled(id, enabled)
            refresh()
        }
        if (enabled) {
            MacroEngineService.start(appContext, MacroEngineService.ACTION_ENABLE, id)
        } else {
            MacroEngineService.start(appContext, MacroEngineService.ACTION_DISABLE, id)
        }
    }

    companion object {
        fun factory(
            repository: WorkflowRepository,
            transfers: MacroTransferRepository,
            runLog: RunLog,
            appContext: Context,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { WorkflowListViewModel(repository, transfers, runLog, appContext) }
        }
    }
}
