package com.example.ottomatic.feature.workflowlist

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * The two document-picker round trips Export and Import each need, as one thing the
 * list screen can call without holding either.
 *
 * Extracted from `WorkflowListScreen` rather than written inline because a SAF launcher
 * is not one expression: each is a `rememberLauncherForActivityResult` plus a callback
 * that has to decide what a null result means, and Export additionally has to remember
 * *which* macro the picker was opened for, since the contract hands back only the Uri
 * the user chose. Three pieces of state and two branches, none of which the screen has
 * any reason to see.
 */
class MacroTransferActions internal constructor(
    private val onExport: (id: String, name: String) -> Unit,
    private val onImport: () -> Unit,
) {
    /** Opens the "save as" picker for the macro [id], suggesting a name from [name]. */
    fun export(id: String, name: String) = onExport(id, name)

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
    // Which macro the Export chooser was opened for. It has to survive the trip out to
    // the document picker and back, because the contract's result carries only the Uri.
    var pending by remember { mutableStateOf<String?>(null) }

    val exportTarget = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(EXPORT_MIME_TYPE),
    ) { target ->
        val id = pending
        pending = null
        if (target != null && id != null) viewModel.export(id, target)
    }

    val importSource = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { source -> source?.let(viewModel::import) }

    return remember(viewModel) {
        MacroTransferActions(
            onExport = { id, name ->
                pending = id
                exportTarget.launch(viewModel.suggestedFileName(name))
            },
            onImport = { importSource.launch(IMPORT_MIME_TYPES) },
        )
    }
}
