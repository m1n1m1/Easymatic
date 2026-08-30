package io.github.m1n1m1.easymatic.engine.trigger

import io.github.m1n1m1.easymatic.core.permissions.PermissionRequirement
import io.github.m1n1m1.easymatic.core.permissions.Permissions
import io.github.m1n1m1.easymatic.core.permissions.PrerequisiteType
import io.github.m1n1m1.easymatic.core.trigger.TriggerSource
import io.github.m1n1m1.easymatic.domain.model.NodeCategory
import io.github.m1n1m1.easymatic.domain.model.NodeIcon
import io.github.m1n1m1.easymatic.domain.model.WorkflowNode
import io.github.m1n1m1.easymatic.domain.model.config.NoConfig
import io.github.m1n1m1.easymatic.domain.model.dataOut
import io.github.m1n1m1.easymatic.domain.model.derivedOut
import io.github.m1n1m1.easymatic.domain.model.items.ImageItem
import io.github.m1n1m1.easymatic.engine.NodeOutput
import io.github.m1n1m1.easymatic.engine.triggerNode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

/**
 * `trigger.screenshot` — fires when a screenshot is captured on this phone.
 *
 * **Its own node rather than advice to configure `trigger.image_saved`**, and the reason is
 * the one that node's own KDoc raises and declines to solve: a screenshot lives in
 * `Pictures/Screenshots` on some phones and `DCIM/Screenshots` on others, so the folder
 * field there can only be filled in by somebody who already knows which phone they have.
 * That KDoc rejects a "Camera / Screenshots / Anywhere" *enum* because an enum would invent
 * vocabulary the platform does not have — which is right, and is not an argument against a
 * node whose entire subject is screenshots. Here the user says what they mean and
 * `Screenshots.isScreenshotFolder` resolves what that is, in one place shared with
 * `action.screenshot` and `value.latest_screenshot`.
 *
 * **`NoConfig` on purpose.** Once the folder is the app's business there is nothing left to
 * narrow: a name filter over screenshots would be filtering on a stamp the system generated.
 * Anybody who genuinely wants one still has `trigger.image_saved`, which is the general node
 * this is the specialisation of.
 *
 * **It reports Easymatic's own captures too**, because a screenshot is a screenshot whoever
 * took it, and filtering on the owning package would invent a distinction nobody asked for —
 * one that would also break the moment an OEM's own screenshot tool wrote the row. The cost
 * is that wiring this into `action.screenshot` loops; the watcher's debounce and its
 * `MAX_NEW_PER_SCAN` cap bound it, and the loop is visible on the canvas.
 *
 * Everything else is `trigger.image_saved`'s behaviour, because it is literally the same
 * observer: **arming reports nothing** (a phone full of old screenshots must not fire a
 * hundred macros), a burst is capped, and each node keeps its own high-water mark. The
 * `path` projection is a `derivedOut` for the same reason as there — it is the handle
 * `action.ai_describe` and `action.image_edit` take, so the commonest wiring needs no
 * `action.break`.
 */
class ScreenshotTrigger : Trigger<NoConfig, ImageItem> {

    override val definition = triggerNode<NoConfig, ImageItem>(
        typeId = "trigger.screenshot",
        displayName = "Screenshot Taken",
        description = "Starts when a screenshot is captured on this phone",
        category = NodeCategory.PHONE_MEDIA,
        icon = NodeIcon.SCREENSHOT,
        output = dataOut<ImageItem>("image", label = "Picture"),
        extraOutputs = listOf(
            derivedOut<String, ImageItem>("path", label = "Path") { it.path },
        ),
        permissions = listOf(READ_MEDIA_IMAGES),
    )

    override fun activate(
        config: NoConfig,
        node: WorkflowNode,
        host: TriggerHost,
    ): Flow<NodeOutput<ImageItem>> = flow {
        val watch = host.armImageWatch(
            nodeId = node.id,
            spec = ImageWatchSpec(kind = ImageWatchKind.SCREENSHOT),
        ) { message, level -> host.report(node, message, level) }
        try {
            host.busEvents()
                .filter { it.source == TriggerSource.MEDIA_STORE && it.triggerNodeId == node.id }
                .filter { it.payload[ImageEventCodec.TRIGGER_TYPE] == ImageEventCodec.TYPE_SCREENSHOT }
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
