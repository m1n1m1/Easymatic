package io.github.m1n1m1.easymatic.engine.trigger

import io.github.m1n1m1.easymatic.core.permissions.PermissionRequirement
import io.github.m1n1m1.easymatic.core.permissions.Permissions
import io.github.m1n1m1.easymatic.core.permissions.PrerequisiteType
import io.github.m1n1m1.easymatic.core.service.ImageRecord
import io.github.m1n1m1.easymatic.core.trigger.TriggerSource
import io.github.m1n1m1.easymatic.domain.model.NodeCategory
import io.github.m1n1m1.easymatic.domain.model.NodeIcon
import io.github.m1n1m1.easymatic.domain.model.WorkflowNode
import io.github.m1n1m1.easymatic.domain.model.config.FilePath
import io.github.m1n1m1.easymatic.domain.model.config.Label
import io.github.m1n1m1.easymatic.domain.model.dataOut
import io.github.m1n1m1.easymatic.domain.model.derivedOut
import io.github.m1n1m1.easymatic.domain.model.items.ImageItem
import io.github.m1n1m1.easymatic.domain.model.schema.DateTime
import io.github.m1n1m1.easymatic.engine.NodeOutput
import io.github.m1n1m1.easymatic.engine.triggerNode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable

/**
 * Config for `trigger.image_saved`.
 *
 * **Two filters and no "Camera / Screenshots / Anywhere" choice**, which was the first
 * shape tried and is wrong for the reason `@WifiNetwork` exists: that enum would be
 * inventing vocabulary the platform does not have. "The camera folder" is `DCIM/Camera` on
 * most phones and something else on some, and screenshots move between
 * `Pictures/Screenshots` and `DCIM/Screenshots` by manufacturer — so an enum would be a
 * promise the app cannot keep on the phones where it matters, and there would be no way
 * for the user to correct it. A folder path is what the collection actually stores, it is
 * legible, the field's own chooser fills it in, and blank means anywhere.
 */
@Serializable
data class ImageSavedConfig(
    @Label("Only in this folder")
    @FilePath
    val folder: String = "",
    @Label("Only names matching")
    val pattern: String = "",
)

/**
 * `trigger.image_saved` — fires when a picture is added to the phone.
 *
 * **A photo taken, a screenshot, a download, a picture saved out of a messenger** — every
 * one of them is a row appearing in the media collection, and this reports all of them.
 * That is wider than "when I take a photo" and it is the honest shape: the collection is
 * what the platform notifies on, and there is nothing in the notification that says a
 * camera produced it. Narrowing to the camera is [ImageSavedConfig.folder], which is a
 * fact the user can see and correct.
 *
 * **It carries the picture, unlike `trigger.calendar_changed`.** The observer behind both
 * says only that something changed, but here the watcher can go and *look* — a row has an
 * id, and the diff behind it works out which rows are new. That is why this has a data
 * port where the calendar's has none: there, nothing in the provider's notification
 * identifies what moved, so an `event` port would be a socket handing the consumer
 * nothing.
 *
 * **Arming reports nothing.** A macro armed on a phone with four thousand photos on it
 * must not run four thousand times, so the first arm records where the collection is and
 * fires only for what arrives afterwards — `MailSeenStore`'s bootstrap, and the mark
 * survives a re-arm and a reboot for its reasons.
 *
 * **A burst is capped**, at `ImageLimits.MAX_NEW_PER_SCAN`. Importing five hundred
 * pictures is not five hundred runs of a macro; the newest are reported and the run log
 * says how many were passed over, because a silent cap reads as the trigger being
 * unreliable.
 *
 * The `path` projection is a `derivedOut` on `trigger.message`'s reasoning: it is the
 * handle a sibling node takes — `action.ai_describe`, `action.image_edit` — so it gets its
 * own scalar port and the commonest wiring needs no `action.break`.
 */
class ImageSavedTrigger : Trigger<ImageSavedConfig, ImageItem> {

    override val definition = triggerNode<ImageSavedConfig, ImageItem>(
        typeId = "trigger.image_saved",
        displayName = "Picture Saved",
        description = "Starts when a photo, screenshot or downloaded picture is added to this phone",
        category = NodeCategory.PHONE_MEDIA,
        icon = NodeIcon.IMAGE,
        output = dataOut<ImageItem>("image", label = "Picture"),
        extraOutputs = listOf(
            derivedOut<String, ImageItem>("path", label = "Path") { it.path },
        ),
        permissions = listOf(READ_MEDIA_IMAGES),
    )

    override fun activate(
        config: ImageSavedConfig,
        node: WorkflowNode,
        host: TriggerHost,
    ): Flow<NodeOutput<ImageItem>> = flow {
        val watch = host.armImageWatch(
            nodeId = node.id,
            spec = ImageWatchSpec(folder = config.folder.trim(), pattern = config.pattern.trim()),
        ) { message, level -> host.report(node, message, level) }
        try {
            host.busEvents()
                .filter { it.source == TriggerSource.MEDIA_STORE && it.triggerNodeId == node.id }
                .filter { it.payload[ImageEventCodec.TRIGGER_TYPE] == ImageEventCodec.TYPE_IMAGE }
                .map { event -> NodeOutput(ImageEventCodec.decode(event.payload).toItem()) }
                .collect { emit(it) }
        } finally {
            watch.cancel()
        }
    }

    private companion object {
        val READ_MEDIA_IMAGES = PermissionRequirement(
            manifestPermission = Permissions.READ_MEDIA_IMAGES.manifest,
            type = PrerequisiteType.RUNTIME,
            rationaleKey = "media.read",
        )
    }
}

/**
 * The one place a facade record becomes the graph's struct.
 *
 * `CalendarEventRecord.toItem()`'s arrangement, and it exists for the boundary rather than
 * for tidiness: `core` may not import `domain`, so the record carries epoch millis and the
 * item carries [DateTime]. Doing the conversion in one function is what stops two nodes
 * disagreeing about whether -1 means "unknown" or "1970".
 */
internal fun ImageRecord.toItem(): ImageItem = ImageItem(
    uri = uri,
    path = path,
    name = name,
    folder = folder,
    mimeType = mimeType,
    width = width,
    height = height,
    sizeBytes = sizeBytes,
    takenAt = takenAtEpochMs.takeIf { it > 0 }?.let { DateTime(it) },
    addedAt = addedAtEpochMs.takeIf { it > 0 }?.let { DateTime(it) },
)
