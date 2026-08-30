package io.github.m1n1m1.easymatic.engine.action

import io.github.m1n1m1.easymatic.core.permissions.PermissionRequirement
import io.github.m1n1m1.easymatic.core.permissions.Permissions
import io.github.m1n1m1.easymatic.core.permissions.PrerequisiteType
import io.github.m1n1m1.easymatic.core.service.ImageFacts
import io.github.m1n1m1.easymatic.core.service.LogLevel
import io.github.m1n1m1.easymatic.domain.model.NodeCategory
import io.github.m1n1m1.easymatic.domain.model.NodeIcon
import io.github.m1n1m1.easymatic.domain.model.config.FilePath
import io.github.m1n1m1.easymatic.domain.model.config.Label
import io.github.m1n1m1.easymatic.domain.model.config.Wired
import io.github.m1n1m1.easymatic.domain.model.dataOut
import io.github.m1n1m1.easymatic.domain.model.items.ImageDetailsItem
import io.github.m1n1m1.easymatic.domain.model.schema.DateTime
import io.github.m1n1m1.easymatic.engine.Action
import io.github.m1n1m1.easymatic.engine.ExecutionContext
import io.github.m1n1m1.easymatic.engine.NodeOutput
import io.github.m1n1m1.easymatic.engine.actionNode
import kotlinx.serialization.Serializable

/** Config for `action.image_info`. */
@Serializable
data class ImageInfoConfig(
    @Label("Picture")
    @FilePath
    @Wired
    val image: String = "",
)

/**
 * `action.image_info` — everything the phone knows about one picture.
 *
 * **The collection's columns and the file's own EXIF in one struct**, because nobody
 * asking "what camera took this?" is thinking about which of the two answers it. The
 * struct is flat for the same reason: one `action.break` reaches the width, the camera
 * model and the location alike.
 *
 * **It reads the file rather than the cursor wherever both could answer.** MediaStore
 * caches `DATE_TAKEN`, `ORIENTATION` and the coordinates as columns, and those are copies
 * of what EXIF said when the row was indexed — so after anything rewrites the file they
 * are stale, and a node reporting them would contradict the picture it is describing.
 * That costs one file open, which is why this is an action and `value.latest_image` is not.
 *
 * **It declares `ACCESS_MEDIA_LOCATION` statically**, which is the opposite call from
 * `action.call`'s contacts access and worth saying why. That one is derived from config
 * (`usesContacts`) because a typed phone number needs no address book; here there is no
 * configuration of this node in which the grant is not wanted — the struct always carries
 * a latitude and a longitude — so the badge is never a lie.
 *
 * **Three states for the location, not two.** `hasLocation` false means the picture has
 * none; `locationHidden` true means it has one and Android will not show it, which from
 * Android 10 is what a missing grant produces. Collapsing them would make a revoked
 * permission look like a photo taken nowhere, which is the same mistake `FileInfoItem`
 * avoids by keeping `exists` beside `error`.
 */
class ImageInfoAction : Action<ImageInfoConfig, ImageDetailsItem> {

    override val definition = actionNode<ImageInfoConfig, ImageDetailsItem>(
        typeId = "action.image_info",
        displayName = "Picture Details",
        description = "Reads a picture's size, camera, date and location out of the file",
        category = NodeCategory.IMAGES,
        icon = NodeIcon.IMAGE,
        // Named `details` rather than `image` because the config field is already
        // `image`: a node may legally have an input and an output of one name, but
        // the generated string keys are `port_<typeid>_<port>` with no direction in
        // them, so the two would collide into one translatable label.
        output = dataOut<ImageDetailsItem>("details", label = "Details"),
        permissions = listOf(MEDIA_READ_PERMISSION, MEDIA_LOCATION_PERMISSION),
    )

    override suspend fun execute(
        input: ImageInfoConfig,
        context: ExecutionContext,
    ): NodeOutput<ImageDetailsItem> {
        val handle = input.image.trim()
        if (handle.isBlank()) {
            val problem = "No picture named, so there is nothing to look at"
            context.log(problem, LogLevel.ERROR)
            return NodeOutput(ImageDetailsItem(exists = false, error = problem))
        }
        val facts = context.images.details(handle)
        when {
            facts.error.isNotBlank() -> context.log(facts.error, LogLevel.WARN)
            !facts.exists -> context.log("There is no picture at $handle")
            facts.locationHidden -> context.log(
                "Read ${facts.image.name}. Android is hiding where it was taken — " +
                    "grant permission to read photo locations to see it.",
            )
            else -> context.log("Read ${facts.image.name}")
        }
        return NodeOutput(facts.toItem())
    }

    private companion object {
        val MEDIA_LOCATION_PERMISSION = PermissionRequirement(
            manifestPermission = Permissions.ACCESS_MEDIA_LOCATION.manifest,
            type = PrerequisiteType.RUNTIME,
            rationaleKey = "media.location",
        )
    }
}

/** The facade's facts as the graph's struct, flattened so one `action.break` reaches it all. */
internal fun ImageFacts.toItem(): ImageDetailsItem = ImageDetailsItem(
    exists = exists,
    uri = image.uri,
    path = image.path,
    name = image.name,
    folder = image.folder,
    mimeType = image.mimeType,
    width = image.width,
    height = image.height,
    sizeBytes = image.sizeBytes,
    takenAt = image.takenAtEpochMs.takeIf { it > 0 }?.let { DateTime(it) },
    addedAt = image.addedAtEpochMs.takeIf { it > 0 }?.let { DateTime(it) },
    orientationDegrees = orientationDegrees,
    cameraMake = cameraMake,
    cameraModel = cameraModel,
    isoSpeed = isoSpeed,
    exposureTime = exposureTime,
    fNumber = fNumber,
    focalLength = focalLength,
    description = description,
    hasLocation = hasLocation,
    latitude = latitude,
    longitude = longitude,
    locationHidden = locationHidden,
    error = error,
)
