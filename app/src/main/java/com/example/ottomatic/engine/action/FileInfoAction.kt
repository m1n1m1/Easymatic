package com.example.ottomatic.engine.action

import com.example.ottomatic.core.service.LogLevel
import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.NodeIcon
import com.example.ottomatic.domain.model.config.FilePath
import com.example.ottomatic.domain.model.config.Label
import com.example.ottomatic.domain.model.config.Wired
import com.example.ottomatic.domain.model.dataOut
import com.example.ottomatic.domain.model.items.FileInfoItem
import com.example.ottomatic.domain.model.schema.DateTime
import com.example.ottomatic.engine.Action
import com.example.ottomatic.engine.ExecutionContext
import com.example.ottomatic.engine.NodeOutput
import com.example.ottomatic.engine.actionNode
import kotlinx.serialization.Serializable

@Serializable
data class FileInfoConfig(
    @Label("File or folder")
    @FilePath
    @Wired
    val path: String = "",
)

/**
 * Answers whether a file is there, and what is known about it.
 *
 * **This is the node that exists instead of a `value.file_exists`**, and the reasoning
 * is worth keeping because it is the pull side's rule doing its job rather than an
 * omission. A value node must be cheap and must not fail; `value.ha_state` and
 * `value.mqtt_topic` clear that bar because a socket keeps a map warm, so the read is a
 * lookup. A filesystem pushes nothing — it answers only when asked — and a folder the
 * user granted may be served by a cloud provider, so the cheapest possible read here is
 * still a round trip that can fail. This is `value.light_state`'s side of that line.
 *
 * Two more reasons, either sufficient. The answer depends **entirely on config**, which
 * is the property already keeping `value.variable` out of `action.if`'s source list,
 * since a `val:` read is performed with no config at all. And a value must fail closed
 * to a single `null`, where "there is no file", "no folder covering this has been
 * granted" and "the provider failed" are three different sentences somebody needs —
 * which is exactly what [FileInfoItem.exists] beside `error` keeps apart.
 *
 * So the shape is this node into `action.if`, on the exec wire where the wait is
 * visible — `action.fetch_mail`'s and `action.light_state`'s arrangement.
 *
 * **Size and modification time are −1 when unknown, never 0.** A document provider may
 * report neither, and cloud-backed ones routinely do not; zero would be a lie a macro
 * would act on, reading an unmeasured file as an empty one.
 */
class FileInfoAction : Action<FileInfoConfig, FileInfoItem> {

    override val definition = actionNode<FileInfoConfig, FileInfoItem>(
        typeId = "action.file_info",
        displayName = "File Details",
        description = "Answers whether a file exists, how big it is and when it changed",
        category = NodeCategory.FILES,
        icon = NodeIcon.FILE,
        output = dataOut<FileInfoItem>("file", label = "File"),
    )

    override suspend fun execute(
        input: FileInfoConfig,
        context: ExecutionContext,
    ): NodeOutput<FileInfoItem> {
        val path = input.path.trim()
        if (path.isBlank()) {
            val problem = "No file named, so there is nothing to look up"
            context.log(problem, LogLevel.ERROR)
            return NodeOutput(FileInfoItem(exists = false, path = "", error = problem))
        }
        val facts = context.files.info(path)
        when {
            facts.error.isNotBlank() -> context.log(facts.error, LogLevel.WARN)
            facts.exists -> context.log("$path is there")
            // Not a warning: "it is not there" is the answer this node exists to give.
            else -> context.log("$path is not there")
        }
        return NodeOutput(
            FileInfoItem(
                exists = facts.exists,
                path = facts.path.ifBlank { path },
                name = facts.name,
                isFolder = facts.isFolder,
                sizeBytes = facts.sizeBytes,
                modified = facts.modifiedEpochMs.takeIf { it > 0 }?.let { DateTime(it) },
                error = facts.error,
            ),
        )
    }
}
