package com.example.ottomatic.engine.action

import com.example.ottomatic.core.service.LogLevel
import com.example.ottomatic.domain.model.FilePath as ParsedPath
import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.NodeIcon
import com.example.ottomatic.domain.model.config.FilePath
import com.example.ottomatic.domain.model.config.Label
import com.example.ottomatic.domain.model.config.Wired
import com.example.ottomatic.domain.model.dataOut
import com.example.ottomatic.domain.model.items.FileResultItem
import com.example.ottomatic.engine.Action
import com.example.ottomatic.engine.ExecutionContext
import com.example.ottomatic.engine.NodeOutput
import com.example.ottomatic.engine.actionNode
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Whether the original stays where it was.
 *
 * Two members and not three. Renaming is a move whose destination is in the same folder,
 * so a `RENAME` member would be a special case of `MOVE` that switched nothing — the
 * mode-enum smell. The saving it might have bought is taken anyway and invisibly: the
 * store notices a same-folder move and asks the provider to rename, which is one call
 * instead of a copy.
 */
@Serializable
enum class TransferOp {
    @Label("Copy it")
    @SerialName("copy")
    COPY,

    @Label("Move it")
    @SerialName("move")
    MOVE,
}

@Serializable
data class FileTransferConfig(
    @Label("From")
    @FilePath
    @Wired
    val from: String = "",
    /**
     * Where it goes — a file path, or a **folder** to put it in under its own name.
     *
     * Both, because both are what people mean and the field cannot tell them apart on
     * its own: this field's own chooser fills in a folder with a trailing separator,
     * so "copy it into that folder" is the commonest thing anybody will express here.
     */
    @Label("To")
    @FilePath
    @Wired
    val to: String = "",
    @Label("What to do")
    val operation: TransferOp = TransferOp.COPY,
    @Label("If a file is already there")
    val whenExists: WriteCollision = WriteCollision.REPLACE,
)

/**
 * Copies or moves a file, and renames one.
 *
 * **One node rather than three**, because the declared ports are identical and the
 * operations genuinely are one operation with a flag — which is the argument
 * `action.mail_update` makes, used where it holds. Renaming is a move within one
 * folder, so it needs no member of its own; see [TransferOp].
 *
 * **The destination may name a folder**, in which case the file goes inside it under
 * its own name — `cp a.txt Archive/`'s rule. That is the ordinary case rather than a
 * convenience, because this node's own chooser fills the field in with a folder.
 *
 * **Putting a file where it already is is refused**, unless the collision rule is
 * "keep both", where it is the ordinary way to duplicate a file and the provider gives
 * the copy a new name. The refusal is not fussiness: the copy opens the source for
 * reading and then opens the same path for writing, which truncates it, so attempting
 * it would finish having emptied the file it was meant to copy.
 *
 * **This works on any file, including binary ones.** The graph having no bytes type
 * limits what can be carried *through* it, not what can be moved *on the disk* — the
 * content never becomes an item here, it goes from one stream to another. So "when a
 * photo lands in the camera folder, move it to Archive" is entirely expressible even
 * though reading that photo is not.
 *
 * **The copy is a stream copy and not `DocumentsContract.moveDocument`**, which is
 * deliberate rather than an oversight. That call needs a flag providers may not set,
 * and it works **within one provider only** — so it could serve some transfers and not
 * others, and the ones it could not serve are the interesting ones: out of Ottomatic's
 * own storage, or between two separately granted folders. One path that always works
 * beats a fast path with an unpredictable fallback.
 *
 * **A move deletes only after the copy has landed**, so an interrupted move leaves the
 * original where it was. If the copy succeeds and the delete does not, the receipt says
 * so in its `error` while still reporting `changed` — because a file that now exists in
 * two places is a different problem from one that was never copied, and a macro that
 * treated them alike would copy it a second time.
 */
class FileTransferAction : Action<FileTransferConfig, FileResultItem> {

    override val definition = actionNode<FileTransferConfig, FileResultItem>(
        typeId = "action.file_transfer",
        displayName = "Copy or Move File",
        description = "Copies, moves or renames a file on the phone",
        category = NodeCategory.FILES,
        icon = NodeIcon.FILE,
        output = dataOut<FileResultItem>("state", label = "Result"),
    )

    @Suppress("ReturnCount") // Nothing named, a self-copy and the transfer are three outcomes.
    override suspend fun execute(
        input: FileTransferConfig,
        context: ExecutionContext,
    ): NodeOutput<FileResultItem> {
        val from = input.from.trim()
        if (from.isBlank() || input.to.isBlank()) {
            val problem = "Both a file to take and a place to put it are needed"
            context.log(problem, LogLevel.ERROR)
            return NodeOutput(FileResultItem(changed = false, error = problem))
        }
        val to = destinationFor(from, input.to.trim(), context)
        // Refused rather than attempted, because attempting it destroys the file: the
        // copy opens the source for reading and then opens the same path for writing,
        // which truncates it — so the "copy" would finish having emptied the original.
        // "Keep both" is exempt because there it is not a self-copy at all but the
        // ordinary way to duplicate a file, and the provider gives the copy a new name.
        if (to == from && input.whenExists != WriteCollision.KEEP_BOTH) {
            val problem = "$from is already where it would be put — " +
                "choose \"Keep both, adding a number\" to make a second copy of it"
            context.log(problem, LogLevel.ERROR)
            return NodeOutput(FileResultItem(changed = false, path = to, error = problem))
        }
        val move = input.operation == TransferOp.MOVE
        val result = context.files.transfer(from, to, move, input.whenExists.toWhenExists())
        val verb = if (move) "Moved" else "Copied"
        when {
            // A blank-changed error is a real failure; an error beside `changed` is the
            // half-done move above, which is a warning about a file that does exist.
            result.error.isNotBlank() -> context.log(result.error, LogLevel.WARN)
            !result.changed -> context.log("$to was already there, so nothing was ${verb.lowercase()}")
            else -> context.log("$verb $from to ${result.path}")
        }
        return NodeOutput(
            FileResultItem(
                changed = result.changed,
                path = result.path.ifBlank { to },
                name = result.name,
                error = result.error,
            ),
        )
    }

    /**
     * The file path [to] names, resolving a **folder** to a file inside it.
     *
     * `cp a.txt Archive/`'s rule, and it is needed rather than convenient: this node's
     * own chooser fills the field in with a folder and a trailing separator, so a
     * destination naming a folder is the ordinary case rather than a stray one.
     *
     * Two ways to say it, because both occur. A trailing separator is explicit and
     * costs nothing to honour. Without one, the only way to know is to ask — which is
     * one extra lookup on a node that is about to copy the whole file anyway, and the
     * alternative is guessing from whether the last segment looks like it has an
     * extension, which is wrong for `Archive` and `.backup` alike.
     */
    @Suppress("ReturnCount") // An unreadable source, an explicit folder and the probe are three.
    private suspend fun destinationFor(from: String, to: String, context: ExecutionContext): String {
        val name = ParsedPath.parse(from)?.name ?: return to
        if (to.endsWith('/')) return to.trimEnd('/') + "/" + name
        val facts = context.files.info(to)
        return if (facts.exists && facts.isFolder) "$to/$name" else to
    }
}
