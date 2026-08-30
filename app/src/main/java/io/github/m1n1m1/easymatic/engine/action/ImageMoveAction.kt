package io.github.m1n1m1.easymatic.engine.action

import io.github.m1n1m1.easymatic.domain.model.NodeCategory
import io.github.m1n1m1.easymatic.domain.model.NodeIcon
import io.github.m1n1m1.easymatic.domain.model.config.FilePath
import io.github.m1n1m1.easymatic.domain.model.config.Label
import io.github.m1n1m1.easymatic.domain.model.config.Wired
import io.github.m1n1m1.easymatic.domain.model.dataOut
import io.github.m1n1m1.easymatic.domain.model.items.ImageResultItem
import io.github.m1n1m1.easymatic.engine.Action
import io.github.m1n1m1.easymatic.engine.ExecutionContext
import io.github.m1n1m1.easymatic.engine.NodeOutput
import io.github.m1n1m1.easymatic.engine.actionNode
import kotlinx.serialization.Serializable

/** Config for `action.image_move`. */
@Serializable
data class ImageMoveConfig(
    @Label("Picture")
    @FilePath
    @Wired
    val image: String = "",
    @Label("Into folder")
    @FilePath
    @Wired
    val toFolder: String = "",
    @Label("Operation")
    val operation: TransferOp = TransferOp.MOVE,
    @Label("If one is already there")
    val whenExists: WriteCollision = WriteCollision.KEEP_BOTH,
)

/**
 * `action.image_move` — moves or copies a picture into another folder.
 *
 * **A move keeps the same row, which is the whole reason this is not
 * `action.file_transfer`.** From Android 10 the collection is told where a picture lives
 * by a column, so moving one is an update to that column — the row keeps its id, its
 * capture date and its place in every other app's gallery. Copying the bytes to a new
 * file and deleting the old one, which is what a file node necessarily does, would produce
 * a *new photo taken today*: the wrong date, the wrong position in the timeline, and every
 * album and shared link pointing at something that no longer exists. Below Android 10 there
 * is no such column and the copy-then-delete is the only way, so that is what happens
 * there — with the delete only after the copy has landed, on `RoutingFiles.transfer`'s rule.
 *
 * **Copy is the same node rather than a second one**, on `action.file_transfer`'s
 * precedent: one destination, one collision rule, one receipt, and a dropdown for the one
 * word that differs.
 *
 * **It can be refused.** Moving a picture another app saved needs Android's confirmation
 * from Android 11 — the user is asked if the phone is in their hand, and if it is not the
 * node reports `needsConfirmation` so a macro can retry rather than treating it as final.
 *
 * The name may change on the way: MediaStore renames silently on a collision, so the
 * receipt carries the name that now exists rather than the one asked for.
 */
class ImageMoveAction : Action<ImageMoveConfig, ImageResultItem> {

    override val definition = actionNode<ImageMoveConfig, ImageResultItem>(
        typeId = "action.image_move",
        displayName = "Move or Copy Picture",
        description = "Moves or copies a photo into another folder, keeping it in the gallery",
        category = NodeCategory.IMAGES,
        icon = NodeIcon.IMAGE,
        output = dataOut<ImageResultItem>("state", label = "Result"),
        permissions = IMAGE_WRITE_PERMISSIONS,
    )

    @Suppress("ReturnCount") // One exit per missing field, so each names itself.
    override suspend fun execute(
        input: ImageMoveConfig,
        context: ExecutionContext,
    ): NodeOutput<ImageResultItem> {
        val handle = input.image.trim()
        val moving = input.operation == TransferOp.MOVE
        if (handle.isBlank()) return NodeOutput(context.noPictureNamed(if (moving) "move" else "copy"))

        val destination = input.toFolder.trim()
        if (destination.isBlank()) {
            return NodeOutput(context.noFolderNamed(if (moving) "move" else "copy"))
        }

        val result = context.images.transfer(
            ref = handle,
            toFolder = destination,
            move = moving,
            whenExists = input.whenExists.toWhenExists(),
        )
        return NodeOutput(context.reportImageWrite(result, if (moving) "Moved" else "Copied"))
    }
}

/**
 * The receipt for a transfer with no destination.
 *
 * Its own sentence rather than [noPictureNamed]'s, because the two mistakes are different
 * and a node that said "no picture named" when the picture was fine and the folder was
 * blank would send somebody looking in the wrong place.
 */
private fun ExecutionContext.noFolderNamed(verb: String): ImageResultItem {
    val problem = "No folder named, so there is nowhere to $verb the picture to"
    log(problem, io.github.m1n1m1.easymatic.core.service.LogLevel.ERROR)
    return ImageResultItem(changed = false, error = problem)
}
