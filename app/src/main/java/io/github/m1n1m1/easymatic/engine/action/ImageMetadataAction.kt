package io.github.m1n1m1.easymatic.engine.action

import io.github.m1n1m1.easymatic.core.service.MetadataDetail
import io.github.m1n1m1.easymatic.domain.model.NodeCategory
import io.github.m1n1m1.easymatic.domain.model.NodeIcon
import io.github.m1n1m1.easymatic.domain.model.config.FilePath
import io.github.m1n1m1.easymatic.domain.model.config.Hint
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

/**
 * Which detail `action.image_metadata` changes.
 *
 * **An enum rather than a `@Picker` over typed EXIF tag names**, and the deciding test is
 * `PickerKind.HA_ENTITY`'s: the answer set is knowable and *complete*. Every tag Easymatic
 * can write is in this list, so there is nothing a typed one could reach that the list
 * cannot — unlike a Wi-Fi network, whose answer set is every network that exists. A
 * mistyped tag name would otherwise fail exactly the way a mistyped identifier does:
 * silently, by naming something else.
 */
@Serializable
enum class ImageDetailField {
    @Label("Description")
    @SerialName("description")
    DESCRIPTION,

    @Label("Date taken")
    @SerialName("taken")
    DATE_TAKEN,

    @Label("Location")
    @SerialName("location")
    LOCATION,

    @Label("Which way up")
    @SerialName("orientation")
    ORIENTATION,

    @Label("Camera maker")
    @SerialName("make")
    CAMERA_MAKE,

    @Label("Camera model")
    @SerialName("model")
    CAMERA_MODEL,

    @Label("Artist")
    @SerialName("artist")
    ARTIST,

    @Label("Copyright")
    @SerialName("copyright")
    COPYRIGHT,
}

/**
 * Config for `action.image_metadata`.
 *
 * **One detail and one value, where blank removes the tag.** Twelve optional fields was
 * the obvious alternative and is unfixable: with twelve, a blank field has to mean both
 * "leave this alone" and "remove this", and no amount of copy separates them without a
 * checkbox beside every row. One tag per node makes blank mean exactly one thing — and
 * setting three tags is three nodes, which is the graph's grammar rather than a
 * workaround.
 *
 * It also gets "strip the location before I share this" for free, which would otherwise
 * have needed a node of its own.
 */
@Serializable
data class ImageMetadataConfig(
    @Label("Picture")
    @FilePath
    @Wired
    val image: String = "",
    @Label("What to change")
    val detail: ImageDetailField = ImageDetailField.DESCRIPTION,
    @Label("New value")
    @Hint("blank removes it")
    @Wired
    val value: String = "",
)

/**
 * `action.image_metadata` — writes one detail into a picture's own file.
 *
 * **The value goes into the file, not into a database.** That is what makes it travel: a
 * description written here is still there after the photo is copied to a computer or sent
 * to somebody, where a note kept in MediaStore would not survive leaving the phone.
 *
 * **Location is the case worth building this for.** Blank clears every GPS tag, so
 * "before posting this, take the location out" is one node — and because it rewrites the
 * file rather than the index, the stripped photo is genuinely stripped.
 *
 * **It can be refused, and refusal has two flavours.** Changing a picture another app
 * saved needs Android's confirmation from Android 11; if the phone is in the user's hand
 * they are asked, and if it is not, the node reports `needsConfirmation` so a macro can
 * try again later rather than treating it as a permanent failure. Granting media
 * management in Settings removes the question entirely.
 *
 * **Not every format can be written.** Android can read EXIF out of far more kinds of file
 * than it can write back into — HEIC and the raw formats are read-only — and that is
 * checked *before* the write and reported by name. A save that succeeded at every step and
 * changed nothing is the worst outcome available here.
 *
 * `action.image_info` reads the file rather than the collection's cached columns, so the
 * value written here is what it reports back immediately.
 */
class ImageMetadataAction : Action<ImageMetadataConfig, ImageResultItem> {

    override val definition = actionNode<ImageMetadataConfig, ImageResultItem>(
        typeId = "action.image_metadata",
        displayName = "Change Picture Details",
        description = "Writes or removes a picture's description, date, location or camera details",
        category = NodeCategory.IMAGES,
        icon = NodeIcon.IMAGE,
        output = dataOut<ImageResultItem>("state", label = "Result"),
        permissions = IMAGE_WRITE_PERMISSIONS,
    )

    override suspend fun execute(
        input: ImageMetadataConfig,
        context: ExecutionContext,
    ): NodeOutput<ImageResultItem> {
        val handle = input.image.trim()
        if (handle.isBlank()) return NodeOutput(context.noPictureNamed("change"))

        val result = context.images.setMetadata(
            ref = handle,
            detail = input.detail.toFacade(),
            value = input.value.trim(),
        )
        return NodeOutput(context.reportImageWrite(result, "Changed"))
    }
}

private fun ImageDetailField.toFacade(): MetadataDetail = when (this) {
    ImageDetailField.DESCRIPTION -> MetadataDetail.DESCRIPTION
    ImageDetailField.DATE_TAKEN -> MetadataDetail.DATE_TAKEN
    ImageDetailField.LOCATION -> MetadataDetail.LOCATION
    ImageDetailField.ORIENTATION -> MetadataDetail.ORIENTATION
    ImageDetailField.CAMERA_MAKE -> MetadataDetail.CAMERA_MAKE
    ImageDetailField.CAMERA_MODEL -> MetadataDetail.CAMERA_MODEL
    ImageDetailField.ARTIST -> MetadataDetail.ARTIST
    ImageDetailField.COPYRIGHT -> MetadataDetail.COPYRIGHT
}
