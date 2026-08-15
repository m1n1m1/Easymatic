package com.example.ottomatic.engine.action

import com.example.ottomatic.core.service.ListFilter
import com.example.ottomatic.core.service.LogLevel
import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.NodeIcon
import com.example.ottomatic.domain.model.config.FilePath
import com.example.ottomatic.domain.model.config.Label
import com.example.ottomatic.domain.model.config.Wired
import com.example.ottomatic.domain.model.dataOut
import com.example.ottomatic.engine.Action
import com.example.ottomatic.engine.ExecutionContext
import com.example.ottomatic.engine.NodeOutput
import com.example.ottomatic.engine.actionNode
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Which entries a listing includes. */
@Serializable
enum class ListEntries {
    @Label("Files")
    @SerialName("files")
    FILES,

    @Label("Folders")
    @SerialName("folders")
    FOLDERS,

    @Label("Both")
    @SerialName("both")
    BOTH,
}

@Serializable
data class FileListConfig(
    @Label("Folder")
    @FilePath
    @Wired
    val path: String = "",
    /**
     * A glob and deliberately not a regular expression.
     *
     * `*.csv` is what somebody types without being taught anything, and read as a
     * regular expression it is not an error but a *different valid pattern* that
     * matches nothing — which looks exactly like an empty folder.
     */
    @Label("Only names matching")
    @Wired
    val pattern: String = "",
    @Label("List")
    val entries: ListEntries = ListEntries.FILES,
)

/**
 * Lists what is inside a folder.
 *
 * **Emits full paths rather than bare names**, which is the decision here that carries
 * its weight: every other node in this family takes a path, so names would put a
 * `transform.text` between this node and anything that acts on what it found. With
 * paths, `action.for_each` into `action.file_read` is one drag. A name on its own is
 * still recoverable — `action.file_info` answers it, or `transform.split_text` and
 * `transform.list_item` do.
 *
 * **Always sorted by name.** A document provider returns its children in whatever order
 * it likes, and that order is unspecified, so without sorting "the first file" would
 * mean different things on different phones and on the same phone on different days.
 * There is no sort option because `transform.list_sort` exists.
 *
 * **The count is capped and the cap is announced**, `MAX_ITERATIONS`' treatment: a
 * folder with forty thousand files in it should not be built into a list nothing can
 * usefully act on, and it should not do so silently either.
 *
 * One level deep — this does not descend into sub-folders. Listing folders and
 * recursing with `action.for_each` is the expressible version; a recursive flag over a
 * cloud-backed folder would be an unbounded number of network calls behind one tick.
 */
class FileListAction : Action<FileListConfig, List<String>> {

    override val definition = actionNode<FileListConfig, List<String>>(
        typeId = "action.file_list",
        displayName = "List Files",
        description = "Lists the files in a folder, as paths you can loop over",
        category = NodeCategory.FILES,
        icon = NodeIcon.FOLDER,
        output = dataOut<List<String>>("paths", label = "Paths"),
    )

    @Suppress("ReturnCount") // Nothing named, a failure and the answer are three outcomes.
    override suspend fun execute(
        input: FileListConfig,
        context: ExecutionContext,
    ): NodeOutput<List<String>> {
        val path = input.path.trim()
        if (path.isBlank()) {
            context.log("No folder named, so there is nothing to list", LogLevel.ERROR)
            return NodeOutput(emptyList())
        }
        val result = context.files.list(path, input.pattern.trim(), input.entries.toListFilter())
        if (!result.ok) {
            context.log(result.error, LogLevel.WARN)
            return NodeOutput(emptyList())
        }
        if (result.truncated) {
            context.log("$path holds more than the limit, so only the first entries were listed", LogLevel.WARN)
        } else {
            context.log("Found ${result.paths.size} in $path")
        }
        return NodeOutput(result.paths)
    }
}

/** The facade's spelling of what to include. */
private fun ListEntries.toListFilter(): ListFilter = when (this) {
    ListEntries.FILES -> ListFilter.FILES
    ListEntries.FOLDERS -> ListFilter.FOLDERS
    ListEntries.BOTH -> ListFilter.BOTH
}
