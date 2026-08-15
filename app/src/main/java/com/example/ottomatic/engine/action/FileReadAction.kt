package com.example.ottomatic.engine.action

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
import kotlinx.serialization.Serializable

@Serializable
data class FileReadConfig(
    @Label("File")
    @FilePath
    @Wired
    val path: String = "",
    @Label("Text encoding")
    val encoding: FileEncoding = FileEncoding.UTF_8,
    /**
     * What the port carries when the file cannot be read.
     *
     * `action.script`'s and `transform.json_read`'s "If it fails" field, for their
     * reason: a file that is not there is a thing for the macro to decide about, not a
     * reason to stop the graph.
     */
    @Label("If it fails")
    @Wired
    val fallback: String = "",
)

/**
 * Reads a file's contents as text.
 *
 * **Text and only text.** The graph has no bytes type — Text, Number, Yes/no, Date and
 * time, Object and List are all of them — and one would not be worth adding for one
 * node: it would need an `ItemSchema`, a conversion table entry, a port colour, a form
 * field and a `ValueType` member. So decoding is **total**: a byte that is not valid in
 * the chosen encoding becomes a replacement character rather than an error, which means
 * **reading a photo succeeds and yields nonsense**. That is the honest sentence and it
 * is here rather than behind a content sniffer, which would be a second thing to be
 * wrong about.
 *
 * The rest of the family is unaffected by that limit and it is worth saying so plainly:
 * `action.file_transfer`, `action.file_delete`, `action.file_list` and
 * `action.file_info` work on **any** file, photos and archives included, because none
 * of them ever makes the content into an item. "When a photo lands, move it to Archive"
 * is entirely expressible; "read a photo and post it" is not.
 *
 * **The read is bounded while the bytes are being read**, not trimmed afterwards. This
 * runs inside the engine's foreground service alongside every other armed macro, so
 * reading a two-gigabyte video into a string is not a slow read but an out-of-memory
 * kill that takes the lot down. A file longer than the bound still reaches the port,
 * with a warning saying it was cut off — `action.ai_prompt`'s rule, for its reason: it
 * is real output a macro will happily send on, and silence would make half a file look
 * like the whole of one. The honest cost is that half a JSON document parses as nothing
 * downstream, so the warning is the whole diagnosis.
 */
class FileReadAction : Action<FileReadConfig, String> {

    override val definition = actionNode<FileReadConfig, String>(
        typeId = "action.file_read",
        displayName = "Read File",
        description = "Reads a text file from the phone and passes its contents on",
        category = NodeCategory.FILES,
        icon = NodeIcon.FILE,
        output = dataOut<String>("text", label = "Text"),
    )

    @Suppress("ReturnCount") // Nothing named, a failure and the answer are three outcomes.
    override suspend fun execute(input: FileReadConfig, context: ExecutionContext): NodeOutput<String> {
        val path = input.path.trim()
        if (path.isBlank()) {
            context.log("No file named, so there is nothing to read", LogLevel.ERROR)
            return NodeOutput(input.fallback)
        }
        val result = context.files.readText(path, input.encoding.toTextEncoding())
        if (!result.ok) {
            context.log(result.error, LogLevel.WARN)
            return NodeOutput(input.fallback)
        }
        if (result.truncated) {
            context.log("$path was longer than the limit, so only the start of it was read", LogLevel.WARN)
        } else {
            context.log("Read $path")
        }
        return NodeOutput(result.text)
    }
}
