package com.example.ottomatic.feature.macro

import android.content.Context
import android.net.Uri
import com.example.ottomatic.R
import com.example.ottomatic.data.MacroTransferRepository
import com.example.ottomatic.data.WorkflowRepository
import com.example.ottomatic.domain.model.reissuedConfig
import com.example.ottomatic.feature.widget.ManualTriggerRef
import com.example.ottomatic.feature.widget.RunTilePin
import com.example.ottomatic.feature.workflowlist.MacroSharing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The four macro-level actions that are the same wherever they are invoked from.
 *
 * They exist as one class for the reason [MacroActionsMenu] exists as one composable:
 * the list and the editor offer the same menu, and a second implementation of Duplicate
 * behind the second copy of the item is a copy that drifts — one of them re-minting
 * credentials, the other not, is not a difference any user could predict.
 *
 * What is *not* here is what genuinely differs. Deleting is each screen's own, because
 * the editor has a debounced save and a `Workflow` in memory to stand down first, where
 * the list has neither. And whether the graph on disk is current before an export reads
 * it is likewise the caller's problem — the editor flushes, the list has nothing to
 * flush.
 *
 * Every method reports plainly whether it worked, rather than raising a message: what
 * to *say* about a failed export is a screen's decision, and the two screens say it in
 * their own dialog.
 */
class MacroOperations(
    private val repository: WorkflowRepository,
    private val transfers: MacroTransferRepository,
    private val appContext: Context,
) {

    /**
     * Copies [id] into a new macro, disarmed and with its credentials re-minted.
     *
     * Goes through the same [reissuedConfig] the editor's duplicate-*selection* uses, on
     * the same argument: a node must not work or not depending on how it was made, and a
     * copy sharing the original's API token would leave two endpoints behind one secret.
     *
     * Disarmed because a duplicate is a draft. Two identical macros both listening for
     * the same geofence would each fire, which is never what copying one meant.
     */
    suspend fun duplicate(id: String): Boolean {
        val original = repository.load(id) ?: return false
        val copy = repository.create(appContext.getString(R.string.macro_transfer_copy_suffix, original.name))
        repository.save(
            original.copy(
                id = copy.id,
                name = copy.name,
                enabled = false,
                nodes = original.nodes.map { it.copy(config = reissuedConfig(it)) },
            ),
        )
        return true
    }

    /**
     * Writes [id] to [target] as an export file.
     *
     * The text is produced by [MacroTransferRepository], which strips the one credential
     * a graph can hold and gathers the credential-free library entries the macro points
     * at, so what lands on the other phone resolves rather than dangling.
     */
    suspend fun export(id: String, target: Uri): Boolean {
        val text = transfers.exportText(id) ?: return false
        return write(target, text)
    }

    /**
     * Writes [id] into the cache and hands the share sheet a read grant on it.
     *
     * A cache copy rather than the stored file: the stored one holds the live API token
     * and the arming flag, and it lives beside every other macro in a directory no other
     * app may be given a foothold in. See `res/xml/file_paths.xml`.
     */
    suspend fun share(id: String, name: String): Boolean {
        val text = transfers.exportText(id) ?: return false
        return MacroSharing.share(appContext, transfers.fileNameFor(name), text)
    }

    /**
     * The filename to offer the document picker for a macro called [name].
     *
     * Delegated rather than reimplemented here: the sanitising and the `.otto.json`
     * suffix are the export format's business, and a second copy of them in the UI would
     * be free to drift from the one the share path uses.
     */
    fun suggestedFileName(name: String): String = transfers.fileNameFor(name)

    /**
     * Asks the launcher to place a Run tile widget for [trigger] on the home screen.
     *
     * Returns false when the launcher refuses — several launchers do not support pinning
     * at all — so the screen can say so rather than leaving the user waiting for a system
     * dialog that is never going to appear.
     */
    fun pin(trigger: ManualTriggerRef): Boolean = RunTilePin.request(appContext, trigger)

    private suspend fun write(target: Uri, text: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            // "wt" truncates. Without it, overwriting a longer file leaves the tail of
            // the old one past the end of the new JSON, which parses as garbage on the
            // way back in rather than failing at the point of writing.
            appContext.contentResolver.openOutputStream(target, "wt")?.use {
                it.write(text.toByteArray())
            } != null
        }.getOrDefault(false)
    }
}
