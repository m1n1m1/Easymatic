package com.example.ottomatic.feature.workflowlist

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.ottomatic.core.service.RunLog
import com.example.ottomatic.data.WorkflowRepository
import com.example.ottomatic.domain.model.WorkflowSummary
import com.example.ottomatic.engine.service.MacroEngineService
import com.example.ottomatic.engine.validation.GraphValidator
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
     * Reloads the persisted workflow summaries, and validates each graph.
     *
     * Validating means loading every workflow in full, which the summary list
     * deliberately avoids — but a graph is small, the list is short, and this
     * already runs off the main thread. Caching it would mean invalidating the
     * cache on every edit made in another screen.
     */
    fun refresh() {
        viewModelScope.launch {
            val list = repository.list()
            val errors = list.mapNotNull { summary ->
                val workflow = repository.load(summary.id) ?: return@mapNotNull null
                GraphValidator(workflow).validate().errors.size
                    .takeIf { it > 0 }
                    ?.let { summary.id to it }
            }.toMap()
            _uiState.update { it.copy(workflows = list, errors = errors, isLoading = false) }
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
        viewModelScope.launch {
            repository.delete(id)
            refresh()
        }
    }

    fun rename(id: String, name: String) {
        viewModelScope.launch {
            repository.rename(id, name)
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
