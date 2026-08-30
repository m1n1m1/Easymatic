package io.github.m1n1m1.easymatic.feature.macro

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.github.m1n1m1.easymatic.feature.workflowlist.EXPORT_MIME_TYPE

/** Opens the "save as" picker for one macro. */
class MacroExportLauncher internal constructor(
    private val onExport: (id: String, name: String) -> Unit,
) {
    /** Asks for a file to write the macro [id] to, suggesting a name derived from [name]. */
    fun export(id: String, name: String) = onExport(id, name)
}

/**
 * Binds the export picker to [onTargetChosen] for as long as the caller is composed.
 *
 * Its own function rather than three lines at each call site because a SAF launcher is
 * not one expression: it is a `rememberLauncherForActivityResult` plus a callback that
 * has to decide what a null result means, plus the memory of *which* macro the picker
 * was opened for — the contract hands back only the Uri the user chose, so without that
 * the result has nothing to be applied to.
 *
 * A cancelled picker does nothing at all: no message, no state change. Backing out of a
 * file chooser is not a failure and must not be reported as one.
 */
@Composable
fun rememberMacroExport(
    suggestedFileName: (name: String) -> String,
    onTargetChosen: (id: String, target: Uri) -> Unit,
): MacroExportLauncher {
    // Which macro the chooser was opened for. It has to survive the trip out to the
    // document picker and back.
    var pending by remember { mutableStateOf<String?>(null) }

    val target = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(EXPORT_MIME_TYPE),
    ) { chosen ->
        val id = pending
        pending = null
        if (chosen != null && id != null) onTargetChosen(id, chosen)
    }

    return remember(target) {
        MacroExportLauncher { id, name ->
            pending = id
            target.launch(suggestedFileName(name))
        }
    }
}
