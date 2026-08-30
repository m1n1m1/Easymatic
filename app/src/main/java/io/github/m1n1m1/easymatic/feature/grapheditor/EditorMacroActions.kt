package io.github.m1n1m1.easymatic.feature.grapheditor

import android.net.Uri
import io.github.m1n1m1.easymatic.domain.model.Workflow
import io.github.m1n1m1.easymatic.feature.macro.MacroOperations
import io.github.m1n1m1.easymatic.feature.widget.ManualTriggerRef
import io.github.m1n1m1.easymatic.feature.widget.manualTriggers
import io.github.m1n1m1.easymatic.feature.workflowlist.TransferFailure
import io.github.m1n1m1.easymatic.feature.workflowlist.TransferMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The workflow menu's items, as the editor performs them.
 *
 * What each of them *does* is [MacroOperations], shared with the workflow list so the
 * menu the two screens now share is backed by one implementation as well. What is left
 * here is the one thing the editor has that the list does not: **a graph in memory that
 * is newer than the file**. Every action below reads the macro back through the
 * repository, and the editor saves on a debounce — so without [saveNow] first, exporting
 * a macro exports it as it was when the last keystroke settled, minus everything dragged,
 * wired or typed since. Silently, and into a file the user is about to send somebody.
 *
 * Its own class rather than four more methods on [GraphEditorViewModel], which is
 * already the largest thing in the editor and is about the canvas. Nothing here touches
 * the canvas: it is handed a way to save and a way to read the current workflow, and
 * needs no other part of the editor's state.
 */
class EditorMacroActions internal constructor(
    private val operations: MacroOperations,
    private val scope: CoroutineScope,
    private val workflow: () -> Workflow,
    /** Writes the pending edits out, answering false when there is nothing to save. */
    private val saveNow: suspend () -> Boolean,
) {

    /**
     * What to say about the export that just failed, or null when there is nothing.
     *
     * Deliberately not part of `GraphEditorUiState`: that state drives the canvas, and a
     * dialog appearing is no reason to repaint a graph.
     */
    private val _transfer = MutableStateFlow<TransferMessage?>(null)
    val transfer: StateFlow<TransferMessage?> = _transfer.asStateFlow()

    /**
     * This macro's manual triggers, from the graph in memory rather than the one on
     * disk — a button added a second ago is exactly the one somebody is trying to put
     * on their home screen.
     */
    fun manualTriggers(): List<ManualTriggerRef> = workflow().manualTriggers()

    /** Places [trigger]'s Run tile, reporting false when the launcher refuses. */
    fun pin(trigger: ManualTriggerRef): Boolean = operations.pin(trigger)

    /** The filename to offer the document picker, derived from the macro's name. */
    fun suggestedFileName(): String = operations.suggestedFileName(workflow().name)

    /**
     * Copies this macro, disarmed and with its credentials re-minted.
     *
     * Stays in this editor afterwards rather than opening the copy: duplicating from
     * here is how a graph gets a scratch version to try something in, and navigating
     * away would take the original off the screen it was being read from. The copy is on
     * the list when the user next looks at it.
     */
    fun duplicate() {
        scope.launch {
            if (saveNow()) operations.duplicate(workflow().id)
        }
    }

    /** Writes this macro to [target] as an export file. */
    fun export(target: Uri) {
        scope.launch {
            if (!saveNow() || !operations.export(workflow().id, target)) failed()
        }
    }

    /** Hands this macro to the share sheet as a sanitised copy. */
    fun share() {
        scope.launch {
            val macro = workflow()
            if (!saveNow() || !operations.share(macro.id, macro.name)) failed()
        }
    }

    /** Clears the export message once its dialog has been read. */
    fun dismissTransfer() {
        _transfer.value = null
    }

    private fun failed() {
        _transfer.value = TransferMessage.Failed(TransferFailure.EXPORT_FAILED)
    }
}
