package io.github.m1n1m1.easymatic.engine.action

import io.github.m1n1m1.easymatic.domain.model.PlatformWarning
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
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Whether `action.image_delete` can be undone. */
@Serializable
enum class ImageRemoval {
    @Label("Move to the bin")
    @SerialName("bin")
    TRASH,

    @Label("Delete for good")
    @SerialName("delete")
    PERMANENT,
}

/** Config for `action.image_delete`. */
@Serializable
data class ImageDeleteConfig(
    @Label("Picture")
    @FilePath
    @Wired
    val image: String = "",
    @Label("How")
    val removal: ImageRemoval = ImageRemoval.TRASH,
)

/**
 * `action.image_delete` — removes a picture from the phone.
 *
 * **The bin is the default, and that is the reason this node exists rather than
 * `action.file_delete` being pointed at a photo.** From Android 11 the collection has a
 * recoverable trash: a picture put there is gone from the gallery and comes back for
 * thirty days. A file node cannot reach it — it deletes bytes — so a macro that turned out
 * to be wrong about which photo to remove would be unrecoverably wrong. Defaulting to the
 * bin makes the destructive option the one somebody has to choose.
 *
 * **Below Android 11 there is no bin, and the node says so.** It deletes permanently and
 * writes a WARN naming the degradation: "recoverable" quietly becoming "gone" is not
 * something to hide, and it is exactly the kind of difference that only shows up on the
 * one old phone in a household.
 *
 * **Deleting nothing is not a failure.** A picture that was already gone reports
 * `changed = false` with no error, on `FileResultItem`'s rule — it is the same distinction
 * `LightChanged.changed` draws, and it is what lets a tidy-up macro run every night
 * without filling the console with errors.
 *
 * Deleting a picture another app saved needs Android's confirmation from Android 11; see
 * `action.image_metadata` for what happens when there is nobody to ask.
 */
class ImageDeleteAction : Action<ImageDeleteConfig, ImageResultItem> {

    override val definition = actionNode<ImageDeleteConfig, ImageResultItem>(
        typeId = "action.image_delete",
        displayName = "Delete Picture",
        description = "Moves a photo to the bin, or deletes it for good",
        platformWarnings = listOf(PlatformWarning.PICTURE_BIN_UNAVAILABLE),
        category = NodeCategory.IMAGES,
        icon = NodeIcon.IMAGE,
        output = dataOut<ImageResultItem>("state", label = "Result"),
        permissions = IMAGE_WRITE_PERMISSIONS,
    )

    override suspend fun execute(
        input: ImageDeleteConfig,
        context: ExecutionContext,
    ): NodeOutput<ImageResultItem> {
        val handle = input.image.trim()
        if (handle.isBlank()) return NodeOutput(context.noPictureNamed("delete"))

        // Announced *before* the delete rather than after it, because after it the
        // picture is already gone: this is the one line that tells somebody on an older
        // phone that "move to the bin" did not do what the field says.
        if (input.removal == ImageRemoval.TRASH && !context.images.hasRecoverableBin) {
            context.log(
                "This version of Android has no picture bin, so the picture is being deleted for good",
                io.github.m1n1m1.easymatic.core.service.LogLevel.WARN,
            )
        }

        val result = context.images.delete(
            ref = handle,
            toTrash = input.removal == ImageRemoval.TRASH,
        )
        val verb = if (input.removal == ImageRemoval.TRASH) "Moved to the bin:" else "Deleted"
        return NodeOutput(context.reportImageWrite(result, verb))
    }
}
