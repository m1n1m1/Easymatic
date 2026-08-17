package com.example.ottomatic.engine.action

import com.example.ottomatic.core.permissions.PermissionRequirement
import com.example.ottomatic.core.permissions.PrerequisiteType
import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.NodeIcon
import com.example.ottomatic.domain.model.config.FilePath
import com.example.ottomatic.domain.model.config.Label
import com.example.ottomatic.domain.model.config.Wired
import com.example.ottomatic.domain.model.dataOut
import com.example.ottomatic.domain.model.items.ImageResultItem
import com.example.ottomatic.engine.Action
import com.example.ottomatic.engine.ExecutionContext
import com.example.ottomatic.engine.NodeOutput
import com.example.ottomatic.engine.actionNode
import kotlinx.serialization.Serializable

/**
 * Config for `action.screenshot`.
 *
 * **Blank means "where this phone puts screenshots"**, which is `Pictures/Screenshots` on
 * some phones and `DCIM/Screenshots` on others — resolved in code rather than asked for,
 * because it is the platform detail a person configuring a macro has no way to know and no
 * reason to care about. The field is still here for the macro that files its captures
 * somewhere of its own.
 *
 * **No format or quality field**, unlike `action.image_edit`. A screenshot is text and flat
 * colour, which is what PNG is for and what JPEG ruins; offering the choice would mostly
 * offer a way to get it wrong. A macro that genuinely wants a smaller file chains
 * `action.image_edit`, where the trade is visible on the canvas — the same reason the image
 * family is six nodes rather than one with a mode dropdown.
 */
@Serializable
data class ScreenshotConfig(
    @Label("Save in this folder")
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
 * `action.screenshot` — captures the screen and saves it as a picture.
 *
 * **The one image node that creates a picture rather than finding or changing one.** Every
 * other node in the family addresses a photo somebody else's app already saved; this is
 * where a macro gets a picture of its own to work with, which is what makes "when a parcel
 * notification arrives, screenshot it and file it" expressible at all.
 *
 * **It runs on the accessibility service**, the same one behind `trigger.volume_button`, so
 * a user who has already enabled that has nothing further to grant.
 * `AccessibilityService.takeScreenshot` is the only route an ordinary app has to its own
 * screen with no per-run consent dialog — MediaProjection needs a second foreground service
 * and an Activity started from the background, which is blocked for exactly the kind of
 * service the engine runs in. The reasoning in full, including why
 * `GLOBAL_ACTION_TAKE_SCREENSHOT` is not it, lives on `ScreenCapture`.
 *
 * **Android 11 and newer.** Below that the platform has no way to hand an app its own
 * screen, and the node says so rather than failing vaguely.
 *
 * **It saves where the phone saves screenshots**, so a capture lands in the gallery's
 * Screenshots album beside the ones taken with the hardware buttons. One consequence is
 * worth naming: `trigger.screenshot` will therefore fire on these too, because a screenshot
 * is a screenshot whoever took it and an owner filter would be inventing a distinction
 * nobody asked for. Wiring that trigger straight into this node loops — bounded by the
 * watcher's debounce and its `MAX_NEW_PER_SCAN` cap, but still a loop, and visibly one on
 * the canvas.
 *
 * **Never throws.** No accessibility access, an Android older than 11, an app that forbids
 * screenshots, the platform's one-a-second rate limit, a folder that cannot be written —
 * all land on `state` with `changed = false` and the reason in `error`, and `out` still
 * pulses. `action.calendar_add`'s contract, for its reason.
 *
 * It declares **only** the accessibility prerequisite. A row this app creates needs no
 * media grant to write on any version, and the folder lookup degrades to a sensible default
 * without one — so declaring `READ_MEDIA_IMAGES` would badge the node in the Problems panel
 * for a permission it never actually needs.
 */
class ScreenshotAction : Action<ScreenshotConfig, ImageResultItem> {

    override val definition = actionNode<ScreenshotConfig, ImageResultItem>(
        typeId = "action.screenshot",
        displayName = "Take Screenshot",
        description = "Captures what is on the screen right now and saves it as a picture",
        category = NodeCategory.IMAGES,
        icon = NodeIcon.SCREENSHOT,
        output = dataOut<ImageResultItem>("state", label = "Result"),
        permissions = listOf(SCREENSHOT_ACCESSIBILITY),
    )

    override suspend fun execute(
        input: ScreenshotConfig,
        context: ExecutionContext,
    ): NodeOutput<ImageResultItem> {
        val result = context.images.capture(
            toFolder = input.toFolder.trim(),
            name = input.name.trim(),
            whenExists = input.whenExists.toWhenExists(),
        )
        return NodeOutput(context.reportImageWrite(result, "Captured"))
    }

    private companion object {
        /**
         * Accessibility access, asked for as its own rationale rather than reusing
         * `trigger.volume_button`'s.
         *
         * The same switch grants both, but the card on this node has to say what *this*
         * node cannot do without it — `LAUNCH_OVERLAY_PERMISSION` and `OVERLAY_PERMISSION`
         * are two constants over one grant for exactly this reason.
         */
        val SCREENSHOT_ACCESSIBILITY = PermissionRequirement(
            manifestPermission = null,
            type = PrerequisiteType.ACCESSIBILITY_SERVICE,
            rationaleKey = "screen.capture",
        )
    }
}
