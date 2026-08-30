package io.github.m1n1m1.easymatic.feature.grapheditor

import io.github.m1n1m1.easymatic.domain.model.VariableDeclaration
import io.github.m1n1m1.easymatic.feature.variables.GlobalVariablesViewModel
import io.github.m1n1m1.easymatic.feature.variables.VariableLibrary
import io.github.m1n1m1.easymatic.feature.variables.VariableScope
import kotlinx.coroutines.flow.StateFlow

/**
 * Both variable sets as one library, from inside a workflow.
 *
 * The two halves come from different owners and that is the whole reason this class
 * exists: locals live in the graph the [GraphEditorViewModel] is editing, so
 * creating one is an ordinary graph edit that the debounced save persists and the
 * re-arm gate notices; globals live in a repository shared by every editor and the
 * standalone screen, so creating one goes through the activity-scoped ViewModel.
 * Everything above this — the picker, the dock's tab — sees one list of variables
 * and neither knows nor cares which side of that line a given one came from.
 */
class EditorVariableLibrary(
    private val editor: GraphEditorViewModel,
    private val globalVariables: GlobalVariablesViewModel,
) : VariableLibrary {

    override val locals: StateFlow<List<VariableDeclaration>> get() = editor.localVariables

    override val globals: StateFlow<List<VariableDeclaration>> get() = globalVariables.globals

    override val supportsLocals: Boolean = true

    override fun upsert(scope: VariableScope, declaration: VariableDeclaration) = when (scope) {
        VariableScope.LOCAL -> editor.upsertVariable(declaration)
        VariableScope.GLOBAL -> globalVariables.upsert(declaration)
    }

    override fun delete(scope: VariableScope, id: String) = when (scope) {
        VariableScope.LOCAL -> editor.deleteVariable(id)
        VariableScope.GLOBAL -> globalVariables.delete(id)
    }
}
