package com.example.ottomatic.engine.action

import com.example.ottomatic.core.service.LogLevel
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
import kotlinx.serialization.Serializable

@Serializable
data class FileDeleteConfig(
    @Label("File")
    @FilePath
    @Wired
    val path: String = "",
)

/**
 * Deletes a file.
 *
 * **Folders are refused, and that is a safety rule rather than a missing feature.**
 * The two storage backends disagree about what deleting a folder means, and they
 * disagree in the worst possible direction: `File.delete()` on the app's own storage
 * refuses a folder that is not empty, while `DocumentsContract.deleteDocument` on a
 * granted folder removes **the entire subtree**. Same node, same path typed the same
 * way, two opposite outcomes decided by which storage it happened to name — and one of
 * them is not recoverable. So neither is offered, and a recursive delete would have to
 * be its own node whose name said so.
 *
 * **A file that was already gone is `changed = false` with no error.** That is not a
 * failure and is not logged as one, on `LightChanged.changed`'s distinction: a macro
 * that tidies up after itself every evening should not file a warning on the evenings
 * there was nothing to tidy.
 *
 * **No permission declared**, on `action.play_sound`'s rule: a folder granted through
 * the system chooser is access this app was handed, not a permission it holds.
 */
class FileDeleteAction : Action<FileDeleteConfig, FileResultItem> {

    override val definition = actionNode<FileDeleteConfig, FileResultItem>(
        typeId = "action.file_delete",
        displayName = "Delete File",
        description = "Removes a file from the phone",
        category = NodeCategory.FILES,
        icon = NodeIcon.FILE,
        output = dataOut<FileResultItem>("state", label = "Result"),
    )

    override suspend fun execute(
        input: FileDeleteConfig,
        context: ExecutionContext,
    ): NodeOutput<FileResultItem> {
        val path = input.path.trim()
        if (path.isBlank()) {
            val problem = "No file named, so there is nothing to delete"
            context.log(problem, LogLevel.ERROR)
            return NodeOutput(FileResultItem(changed = false, error = problem))
        }
        val result = context.files.delete(path)
        when {
            result.error.isNotBlank() -> context.log(result.error, LogLevel.WARN)
            result.changed -> context.log("Deleted $path")
            else -> context.log("$path was not there, so nothing was deleted")
        }
        return NodeOutput(
            FileResultItem(
                changed = result.changed,
                path = result.path.ifBlank { path },
                name = result.name,
                error = result.error,
            ),
        )
    }
}
