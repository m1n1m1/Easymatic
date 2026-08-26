package com.example.ottomatic.feature.workflowlist

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.example.ottomatic.feature.macro.MacroExportLauncher
import com.example.ottomatic.feature.macro.rememberMacroExport

/**
 * The two document-picker round trips Export and Import each need, as one thing the
 * list screen can call without holding either.
 *
 * Export is [rememberMacroExport], shared with the graph editor, which offers the same
 * item from its own overflow menu. Import stays here: it is where a macro comes *from*,
 * so it belongs to the list rather than to any one macro.
 */
class MacroTransferActions internal constructor(
    private val exporter: MacroExportLauncher,
    private val onImport: () -> Unit,
) {
    /** Opens the "save as" picker for the macro [id], suggesting a name from [name]. */
    fun export(id: String, name: String) = exporter.export(id, name)

    /** Opens the file picker to choose a macro to bring in. */
    fun import() = onImport()
}

/**
 * Binds both pickers to [viewModel] for as long as this screen is composed.
 *
 * A cancelled picker does nothing at all — no message, no state change. Backing out of
 * a file chooser is not a failure and must not be reported as one; the user changed
 * their mind, which is a complete outcome.
 */
@Composable
fun rememberMacroTransfer(viewModel: WorkflowListViewModel): MacroTransferActions {
    val exporter = rememberMacroExport(
        suggestedFileName = viewModel::suggestedFileName,
        onTargetChosen = viewModel::export,
    )

    val importSource = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { source -> source?.let(viewModel::import) }

    return remember(exporter, importSource) {
        MacroTransferActions(
            exporter = exporter,
            onImport = { importSource.launch(IMPORT_MIME_TYPES) },
        )
    }
}
