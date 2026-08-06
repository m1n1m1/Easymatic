package com.example.ottomatic.feature.workflowlist

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.ottomatic.core.service.RunLog
import com.example.ottomatic.data.WorkflowRepository
import com.example.ottomatic.data.trigger.VariableStore
import com.example.ottomatic.domain.model.MacroAccent
import com.example.ottomatic.domain.model.MacroIcon
import com.example.ottomatic.domain.model.WorkflowSummary
import com.example.ottomatic.engine.service.MacroEngineService
import com.example.ottomatic.engine.validation.GraphValidator
import com.example.ottomatic.feature.shortcut.MacroShortcuts
import com.example.ottomatic.feature.widget.MacroSnapshots
import com.example.ottomatic.feature.widget.ManualTriggerRef
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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
)

@Suppress("TooManyFunctions") // CRUD surface over the workflow collection.
class WorkflowListViewModel(
    private val repository: WorkflowRepository,
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
            val workflow = repository.create("New Workflow")
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
     * Asks the launcher to pin [trigger] to the home screen.
     *
     * Returns false when the launcher refuses — several launchers do not support
     * pinning at all — so the screen can say so rather than leaving the user
     * waiting for a system dialog that is never going to appear.
     */
    fun pin(trigger: ManualTriggerRef): Boolean = MacroShortcuts.requestPin(appContext, trigger)

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
            runLog: RunLog,
            appContext: Context,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { WorkflowListViewModel(repository, runLog, appContext) }
        }
    }
}
