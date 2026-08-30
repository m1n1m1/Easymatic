package io.github.m1n1m1.easymatic.engine.action

import io.github.m1n1m1.easymatic.core.service.LogLevel
import io.github.m1n1m1.easymatic.core.service.TextEncoding
import io.github.m1n1m1.easymatic.core.service.WhenExists
import io.github.m1n1m1.easymatic.domain.model.NodeCategory
import io.github.m1n1m1.easymatic.domain.model.NodeIcon
import io.github.m1n1m1.easymatic.domain.model.config.FilePath
import io.github.m1n1m1.easymatic.domain.model.config.Label
import io.github.m1n1m1.easymatic.domain.model.config.Multiline
import io.github.m1n1m1.easymatic.domain.model.config.VisibleWhen
import io.github.m1n1m1.easymatic.domain.model.config.Wired
import io.github.m1n1m1.easymatic.domain.model.dataOut
import io.github.m1n1m1.easymatic.domain.model.items.FileResultItem
import io.github.m1n1m1.easymatic.engine.Action
import io.github.m1n1m1.easymatic.engine.ExecutionContext
import io.github.m1n1m1.easymatic.engine.NodeOutput
import io.github.m1n1m1.easymatic.engine.actionNode
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * What a write does when a file is already there.
 *
 * Not a convenience. `DocumentsContract.createDocument` **cannot overwrite**: handed a
 * name that is taken it creates `notes (1).txt` and reports success. So [KEEP_BOTH] is
 * what the platform does when nobody says otherwise, and a node without this field
 * would quietly do that while its label said "write". [REPLACE] is first because it is
 * what somebody writing a report every morning means.
 */
@Serializable
enum class WriteCollision {
    @Label("Replace it")
    @SerialName("replace")
    REPLACE,

    @Label("Keep both, adding a number")
    @SerialName("keep_both")
    KEEP_BOTH,

    @Label("Leave the old one alone")
    @SerialName("skip")
    SKIP,
}

/** How text becomes bytes. Almost always the default; the other two are for old files. */
@Serializable
enum class FileEncoding {
    @Label("UTF-8")
    @SerialName("utf8")
    UTF_8,

    @Label("UTF-16")
    @SerialName("utf16")
    UTF_16,

    @Label("Latin-1 (ISO 8859-1)")
    @SerialName("latin1")
    ISO_8859_1,
}

@Serializable
data class FileWriteConfig(
    @Label("File")
    @FilePath
    @Wired
    val path: String = "",
    @Label("Text")
    @Multiline
    @Wired
    val text: String = "",
    @Label("Add to the end instead of replacing")
    val append: Boolean = false,
    @Label("If a file is already there")
    @VisibleWhen("append", "false")
    val whenExists: WriteCollision = WriteCollision.REPLACE,
    @Label("Text encoding")
    val encoding: FileEncoding = FileEncoding.UTF_8,
)

/**
 * Writes text to a file.
 *
 * **One path field and no storage-area chooser**, which is the whole shape of the file
 * family: a person says where the file is, and whether that means Easymatic's own
 * storage or a folder they granted through the system chooser is a question the router
 * answers. A path with no folder in it — `report.csv` — is kept in the app's own
 * storage and needs no permission from anybody; anything else is an ordinary path and
 * needs the covering folder to have been granted once, on the Folder access screen or
 * with this field's own chooser.
 *
 * **The folders above the file are created**, so `Reports/2026/august.csv` works on the
 * first run rather than needing a separate node to make the folder first.
 *
 * **Appending is read-then-write rather than an append-mode stream**, and the reason is
 * a silent one: `openOutputStream(uri, "wa")` is not honoured by every document
 * provider, and one that ignores the `a` **truncates** — turning "add a line to the
 * log" into "replace the log", on some phones only. It costs a read and is the same
 * everywhere. A file too large to read is refused rather than half-appended.
 *
 * **The receipt carries the name that now exists**, which is not always the one asked
 * for: see [FileResultItem]. A write that was skipped reports `changed = false` with no
 * error, because declining to overwrite is the field doing its job.
 *
 * **A failure lands on the port and pulses `out`**, on `action.script`'s and
 * `transform.json_read`'s contract. **No permission declared**: a folder granted
 * through the system chooser is access this app was handed, not a permission it holds,
 * which is `action.play_sound`'s rule for a chosen sound.
 */
class FileWriteAction : Action<FileWriteConfig, FileResultItem> {

    override val definition = actionNode<FileWriteConfig, FileResultItem>(
        typeId = "action.file_write",
        displayName = "Write to File",
        description = "Saves text to a file on the phone, replacing it or adding to the end",
        category = NodeCategory.FILES,
        icon = NodeIcon.FILE,
        output = dataOut<FileResultItem>("state", label = "Result"),
    )

    override suspend fun execute(
        input: FileWriteConfig,
        context: ExecutionContext,
    ): NodeOutput<FileResultItem> {
        val path = input.path.trim()
        if (path.isBlank()) {
            val problem = "No file named, so there is nothing to write to"
            context.log(problem, LogLevel.ERROR)
            return NodeOutput(FileResultItem(changed = false, error = problem))
        }
        val result = context.files.writeText(
            path = path,
            text = input.text,
            append = input.append,
            whenExists = input.whenExists.toWhenExists(),
            encoding = input.encoding.toTextEncoding(),
        )
        when {
            result.error.isNotBlank() -> context.log(result.error, LogLevel.WARN)
            !result.changed -> context.log("$path was already there, so nothing was written")
            // Worth an explicit line: the provider having chosen a different name is
            // the surprise a macro's next node will otherwise trip over.
            result.name.isNotBlank() && !path.endsWith(result.name) ->
                context.log("Wrote ${result.path} — the name was changed to ${result.name}", LogLevel.WARN)
            else -> context.log("Wrote ${result.path}")
        }
        return NodeOutput(
            FileResultItem(
                changed = result.changed,
                path = result.path,
                name = result.name,
                error = result.error,
            ),
        )
    }
}

/** The facade's spelling of a collision rule. */
internal fun WriteCollision.toWhenExists(): WhenExists = when (this) {
    WriteCollision.REPLACE -> WhenExists.REPLACE
    WriteCollision.KEEP_BOTH -> WhenExists.KEEP_BOTH
    WriteCollision.SKIP -> WhenExists.SKIP
}

/** The facade's spelling of an encoding. */
internal fun FileEncoding.toTextEncoding(): TextEncoding = when (this) {
    FileEncoding.UTF_8 -> TextEncoding.UTF_8
    FileEncoding.UTF_16 -> TextEncoding.UTF_16
    FileEncoding.ISO_8859_1 -> TextEncoding.ISO_8859_1
}
