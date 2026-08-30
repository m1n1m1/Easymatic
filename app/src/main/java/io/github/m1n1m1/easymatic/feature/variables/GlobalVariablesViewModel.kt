package io.github.m1n1m1.easymatic.feature.variables

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.m1n1m1.easymatic.data.GlobalVariableRepository
import io.github.m1n1m1.easymatic.domain.model.VariableDeclaration
import io.github.m1n1m1.easymatic.domain.registry.GlobalVariables
import io.github.m1n1m1.easymatic.engine.service.MacroEngineService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Drives the global variable library.
 *
 * Activity-scoped, like
 * [io.github.m1n1m1.easymatic.feature.geofence.GeofencePlacesViewModel] and for the same
 * reason: the standalone screen and every config picker in every open editor must
 * see one instance, or an edit made in one would be invisible in the other.
 *
 * It is *not* a [VariableLibrary] itself — the editor pairs it with a workflow's
 * own declarations (see `GraphEditorScreen`), and the globals screen wraps it in
 * [GlobalOnlyLibrary], which has no locals to offer.
 */
class GlobalVariablesViewModel(
    private val repository: GlobalVariableRepository,
    private val appContext: Context,
) : ViewModel() {

    val globals: StateFlow<List<VariableDeclaration>> = repository.variables

    fun upsert(declaration: VariableDeclaration) = mutate { repository.upsert(declaration) }

    fun delete(id: String) = mutate { repository.delete(id) }

    /**
     * Applies [change], republishes the library and re-arms the engine.
     *
     * Both follow-ups are load-bearing. [GlobalVariables] is what `effectivePorts`
     * and `GraphValidator` resolve a global reference through, so without the
     * re-publish a retyped variable would leave every card in every open editor
     * wearing its old port colour. And a runner snapshots the declarations when it
     * arms, so without the re-arm a live macro would keep the old type, the old
     * initial value and the old constant flag until something restarted it — the
     * same reason editing a geofence place re-arms.
     */
    private fun mutate(change: suspend () -> Unit) {
        viewModelScope.launch {
            change()
            GlobalVariables.hydrate(repository.list())
            runCatching { MacroEngineService.start(appContext, MacroEngineService.ACTION_REARM_CHANGED) }
        }
    }

    companion object {
        fun factory(
            repository: GlobalVariableRepository,
            appContext: Context,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { GlobalVariablesViewModel(repository, appContext) }
        }
    }
}

/**
 * The globals alone, as a [VariableLibrary] — what the standalone screen shows and
 * what a picker falls back to when there is no workflow in scope.
 *
 * `locals` is a constant empty flow rather than null so that every consumer can
 * collect unconditionally; [VariableLibrary.supportsLocals] is what actually hides
 * the section, and what stops a "New variable" here offering a scope it cannot
 * write to.
 */
class GlobalOnlyLibrary(private val viewModel: GlobalVariablesViewModel) : VariableLibrary {

    override val locals: StateFlow<List<VariableDeclaration>> = MutableStateFlow(emptyList())

    override val globals: StateFlow<List<VariableDeclaration>> get() = viewModel.globals

    override val supportsLocals: Boolean = false

    override fun upsert(scope: VariableScope, declaration: VariableDeclaration) {
        viewModel.upsert(declaration)
    }

    override fun delete(scope: VariableScope, id: String) {
        viewModel.delete(id)
    }
}
