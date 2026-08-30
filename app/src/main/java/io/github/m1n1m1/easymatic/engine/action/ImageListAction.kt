package io.github.m1n1m1.easymatic.engine.action

import io.github.m1n1m1.easymatic.core.service.ImageQuery
import io.github.m1n1m1.easymatic.core.service.LogLevel
import io.github.m1n1m1.easymatic.domain.model.NodeCategory
import io.github.m1n1m1.easymatic.domain.model.NodeIcon
import io.github.m1n1m1.easymatic.domain.model.config.FilePath
import io.github.m1n1m1.easymatic.domain.model.config.Label
import io.github.m1n1m1.easymatic.domain.model.config.Wired
import io.github.m1n1m1.easymatic.domain.model.dataOut
import io.github.m1n1m1.easymatic.domain.model.items.ImageItem
import io.github.m1n1m1.easymatic.domain.model.schema.DateTime
import io.github.m1n1m1.easymatic.engine.Action
import io.github.m1n1m1.easymatic.engine.ExecutionContext
import io.github.m1n1m1.easymatic.engine.NodeOutput
import io.github.m1n1m1.easymatic.engine.actionNode
import io.github.m1n1m1.easymatic.engine.trigger.toItem
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Which end of the collection `action.image_list` starts from. */
@Serializable
enum class ImageOrder {
    @Label("Newest first")
    @SerialName("newest")
    NEWEST_FIRST,

    @Label("Oldest first")
    @SerialName("oldest")
    OLDEST_FIRST,
}

/**
 * Config for `action.image_list`.
 *
 * Every filter is optional and blank means "no filter", which is the shape
 * `action.file_list` and `action.calendar_query` both take. [takenAfter] and [takenBefore]
 * are nullable `DateTime`s so the field is **clearable** — that is how a range says it is
 * unbounded, and a read-only picker could never express it.
 */
@Serializable
data class ImageListConfig(
    @Label("Folder")
    @FilePath
    @Wired
    val folder: String = "",
    @Label("Names matching")
    @Wired
    val pattern: String = "",
    @Label("Taken after")
    val takenAfter: DateTime? = null,
    @Label("Taken before")
    val takenBefore: DateTime? = null,
    @Label("Order")
    val order: ImageOrder = ImageOrder.NEWEST_FIRST,
    @Label("Most to return")
    val limit: Int = ImageQuery.DEFAULT_LIMIT,
)

/**
 * `action.image_list` — finds pictures on the phone.
 *
 * **This is what MediaStore is for and a folder grant is not.** "The ten newest photos",
 * "everything in Screenshots", "pictures taken last Tuesday" are questions about a
 * *collection*, and the file nodes cannot answer any of them: `action.file_list` knows one
 * directory and nothing about when a photo was taken.
 *
 * **It answers a list of pictures rather than a list of paths**, which is where it parts
 * company with `action.file_list` and the reason is what the two are for. There, a path is
 * the only thing there is to say about a file, and emitting names would put a
 * `transform.text` in front of every node that acts on one. Here every row already carries
 * its size, its dimensions and when it was taken — read in the same cursor, at no extra
 * cost — and throwing that away would make "find the big ones" a query per picture.
 * `action.for_each` into `action.break` reaches the path exactly as before.
 *
 * A missing grant is a **failure with an empty list**, not an empty list on its own: an
 * empty gallery and a revoked permission must not look alike.
 */
class ImageListAction : Action<ImageListConfig, List<ImageItem>> {

    override val definition = actionNode<ImageListConfig, List<ImageItem>>(
        typeId = "action.image_list",
        displayName = "Find Pictures",
        description = "Finds photos and screenshots on this phone onto a list you can loop over",
        category = NodeCategory.IMAGES,
        icon = NodeIcon.IMAGE,
        output = dataOut<List<ImageItem>>("images", label = "Pictures"),
        permissions = listOf(MEDIA_READ_PERMISSION),
    )

    override suspend fun execute(
        input: ImageListConfig,
        context: ExecutionContext,
    ): NodeOutput<List<ImageItem>> {
        val listing = context.images.query(
            ImageQuery(
                folder = input.folder.trim(),
                pattern = input.pattern.trim(),
                takenAfterEpochMs = input.takenAfter?.epochMs ?: ImageQuery.UNBOUNDED,
                takenBeforeEpochMs = input.takenBefore?.epochMs ?: ImageQuery.UNBOUNDED,
                newestFirst = input.order == ImageOrder.NEWEST_FIRST,
                limit = input.limit,
            ),
        )
        when {
            listing.error.isNotBlank() -> context.log(listing.error, LogLevel.WARN)
            // Announced rather than enforced silently, on FileLimits' rule: a truncated
            // listing is real output the macro will loop over, so silence would make a
            // slice of the gallery look like the whole of it.
            listing.truncated -> context.log(
                "More pictures matched than the limit of ${input.limit}, so only the first were returned",
                LogLevel.WARN,
            )
            else -> context.log("Found ${listing.images.size} pictures")
        }
        return NodeOutput(listing.images.map { it.toItem() })
    }
}
