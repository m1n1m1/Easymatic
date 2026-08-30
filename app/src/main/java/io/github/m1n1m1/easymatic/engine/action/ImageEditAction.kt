package io.github.m1n1m1.easymatic.engine.action

import io.github.m1n1m1.easymatic.core.service.ImageEdit
import io.github.m1n1m1.easymatic.core.service.ImageFormat
import io.github.m1n1m1.easymatic.core.service.ImageOperation
import io.github.m1n1m1.easymatic.domain.model.NodeCategory
import io.github.m1n1m1.easymatic.domain.model.NodeIcon
import io.github.m1n1m1.easymatic.domain.model.config.FilePath
import io.github.m1n1m1.easymatic.domain.model.config.Label
import io.github.m1n1m1.easymatic.domain.model.config.VisibleWhen
import io.github.m1n1m1.easymatic.domain.model.config.Wired
import io.github.m1n1m1.easymatic.domain.model.dataOut
import io.github.m1n1m1.easymatic.domain.model.items.ImageResultItem
import io.github.m1n1m1.easymatic.engine.Action
import io.github.m1n1m1.easymatic.engine.ExecutionContext
import io.github.m1n1m1.easymatic.engine.NodeOutput
import io.github.m1n1m1.easymatic.engine.actionNode
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** What `action.image_edit` does to the pixels. */
@Serializable
enum class ImageOp {
    @Label("Resize")
    @SerialName("resize")
    RESIZE,

    @Label("Rotate")
    @SerialName("rotate")
    ROTATE,

    @Label("Mirror")
    @SerialName("flip")
    FLIP,

    @Label("Crop")
    @SerialName("crop")
    CROP,

    @Label("Change format or quality")
    @SerialName("convert")
    CONVERT,
}

/** How far `action.image_edit` turns a picture, clockwise. */
@Serializable
enum class ImageTurn {
    @Label("A quarter turn right")
    @SerialName("right")
    RIGHT,

    @Label("Upside down")
    @SerialName("half")
    HALF,

    @Label("A quarter turn left")
    @SerialName("left")
    LEFT,
}

/** Which way `action.image_edit` mirrors a picture. */
@Serializable
enum class ImageMirror {
    @Label("Left to right")
    @SerialName("horizontal")
    HORIZONTAL,

    @Label("Top to bottom")
    @SerialName("vertical")
    VERTICAL,
}

/** What `action.image_edit` writes the result out as. */
@Serializable
enum class ImageSaveFormat {
    @Label("Same as the original")
    @SerialName("same")
    SAME,

    @Label("JPEG")
    @SerialName("jpeg")
    JPEG,

    @Label("PNG")
    @SerialName("png")
    PNG,

    @Label("WebP")
    @SerialName("webp")
    WEBP,
}

/**
 * Config for `action.image_edit`.
 *
 * **One node with an operation dropdown rather than five nodes**, following n8n's Edit
 * Image. The five share everything that matters — the same input, the same memory ceiling,
 * the same destination and the same collision rule — and differ only in a `Matrix`. That
 * is the opposite of the dialog nodes, which are four nodes precisely because their
 * *execution routing* differs.
 *
 * **Format and quality stay visible for every operation**, deliberately. Resizing a PNG
 * screenshot down to a JPEG is the commonest thing anybody does here, and hiding those two
 * behind [ImageOp.CONVERT] would make it two nodes.
 */
@Serializable
data class ImageEditConfig(
    @Label("Picture")
    @FilePath
    @Wired
    val image: String = "",
    @Label("What to do")
    val operation: ImageOp = ImageOp.RESIZE,
    @Label("Longest side (pixels)")
    @Wired
    @VisibleWhen("operation", "resize")
    val maxSide: Int = ImageEdit.DEFAULT_MAX_SIDE,
    @Label("Turn")
    @VisibleWhen("operation", "rotate")
    val turn: ImageTurn = ImageTurn.RIGHT,
    @Label("Mirror")
    @VisibleWhen("operation", "flip")
    val mirror: ImageMirror = ImageMirror.HORIZONTAL,
    @Label("Left")
    @Wired
    @VisibleWhen("operation", "crop")
    val cropX: Int = 0,
    @Label("Top")
    @Wired
    @VisibleWhen("operation", "crop")
    val cropY: Int = 0,
    @Label("Width")
    @Wired
    @VisibleWhen("operation", "crop")
    val cropWidth: Int = 0,
    @Label("Height")
    @Wired
    @VisibleWhen("operation", "crop")
    val cropHeight: Int = 0,
    @Label("Save as")
    val format: ImageSaveFormat = ImageSaveFormat.SAME,
    @Label("Quality")
    val quality: Int = ImageEdit.DEFAULT_QUALITY,
    @Label("Into folder")
    @FilePath
    @Wired
    val toFolder: String = "",
    @Label("File name")
    @Wired
    val name: String = "",
    @Label("If one is already there")
    val whenExists: WriteCollision = WriteCollision.KEEP_BOTH,
)

/**
 * `action.image_edit` — resizes, rotates, mirrors, crops or re-encodes a picture.
 *
 * **It always writes a new file and never edits in place**, which is the decision that
 * shapes everything else about this node. Two things follow, and both are worth having:
 * an original can never be destroyed by a macro that ran while nobody was watching, and
 * the output is a file *this app created* — so it needs no permission and no confirmation
 * on any version of Android, where rewriting somebody else's photo needs both. "Replace
 * the original" is therefore this node followed by `action.image_delete`, which is two
 * visible steps rather than one hidden one.
 *
 * **The memory ceiling is the reason this node is careful.** An edit runs inside
 * `MacroEngineService` beside every armed macro, and a decoded bitmap costs four bytes a
 * pixel — so a 50-megapixel photo decoded whole is two hundred megabytes and is not a slow
 * edit but a kill that takes every other macro with it. The picture is measured before it
 * is decoded, sampled down on the way in, and anything still over
 * `ImageLimits.MAX_DECODE_PIXELS` is shrunk with a line in the run log:
 * `action.ai_prompt`'s truncation rule, because a silently smaller photo is real output a
 * macro will go on to send.
 *
 * **The picture's own rotation tag is applied first, always.** A JPEG that "looks rotated"
 * is nearly always an upright bitmap plus an EXIF tag, so an edit that ignored it would
 * produce a correctly-cropped picture lying on its side.
 *
 * The blank-name default is the source's, with `-edited` on the end; the blank-folder
 * default is `Pictures/Easymatic`. Both are chosen so the node does something sensible
 * with nothing configured, which every node here has to.
 */
class ImageEditAction : Action<ImageEditConfig, ImageResultItem> {

    override val definition = actionNode<ImageEditConfig, ImageResultItem>(
        typeId = "action.image_edit",
        displayName = "Edit Picture",
        description = "Resizes, rotates, mirrors, crops or re-encodes a picture into a new file",
        category = NodeCategory.IMAGES,
        icon = NodeIcon.IMAGE_EDIT,
        output = dataOut<ImageResultItem>("state", label = "Result"),
        // No overlay or write grant: the output is always a file this app created, so
        // nothing here can ever need Android's confirmation.
        permissions = listOf(MEDIA_READ_PERMISSION),
    )

    override suspend fun execute(
        input: ImageEditConfig,
        context: ExecutionContext,
    ): NodeOutput<ImageResultItem> {
        val handle = input.image.trim()
        if (handle.isBlank()) return NodeOutput(context.noPictureNamed("edit"))

        val result = context.images.edit(
            ref = handle,
            edit = ImageEdit(
                operation = input.operation.toFacade(),
                maxSide = input.maxSide,
                turnDegrees = input.turn.degrees,
                flipHorizontal = input.mirror == ImageMirror.HORIZONTAL,
                cropX = input.cropX,
                cropY = input.cropY,
                cropWidth = input.cropWidth,
                cropHeight = input.cropHeight,
                format = input.format.toFacade(),
                quality = input.quality,
                toFolder = input.toFolder.trim(),
                name = input.name.trim(),
                whenExists = input.whenExists.toWhenExists(),
            ),
        )
        return NodeOutput(context.reportImageWrite(result, "Saved"))
    }
}

private fun ImageOp.toFacade(): ImageOperation = when (this) {
    ImageOp.RESIZE -> ImageOperation.RESIZE
    ImageOp.ROTATE -> ImageOperation.ROTATE
    ImageOp.FLIP -> ImageOperation.FLIP
    ImageOp.CROP -> ImageOperation.CROP
    ImageOp.CONVERT -> ImageOperation.CONVERT
}

private fun ImageSaveFormat.toFacade(): ImageFormat = when (this) {
    ImageSaveFormat.SAME -> ImageFormat.SAME
    ImageSaveFormat.JPEG -> ImageFormat.JPEG
    ImageSaveFormat.PNG -> ImageFormat.PNG
    ImageSaveFormat.WEBP -> ImageFormat.WEBP
}

/** Clockwise, because that is the only direction `Matrix.postRotate` counts in. */
private val ImageTurn.degrees: Int
    get() = when (this) {
        ImageTurn.RIGHT -> QUARTER_TURN
        ImageTurn.HALF -> HALF_TURN
        ImageTurn.LEFT -> THREE_QUARTER_TURN
    }

private const val QUARTER_TURN = 90
private const val HALF_TURN = 180
private const val THREE_QUARTER_TURN = 270
