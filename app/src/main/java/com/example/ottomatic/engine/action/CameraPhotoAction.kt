package com.example.ottomatic.engine.action

import com.example.ottomatic.core.permissions.PermissionRequirement
import com.example.ottomatic.core.permissions.Permissions
import com.example.ottomatic.core.permissions.PrerequisiteType
import com.example.ottomatic.core.service.FlashMode
import com.example.ottomatic.core.service.LogLevel
import com.example.ottomatic.core.service.PhotoRequest
import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.NodeIcon
import com.example.ottomatic.domain.model.config.FilePath
import com.example.ottomatic.domain.model.config.Hint
import com.example.ottomatic.domain.model.config.Label
import com.example.ottomatic.domain.model.config.VisibleWhen
import com.example.ottomatic.domain.model.config.Wired
import com.example.ottomatic.domain.model.dataOut
import com.example.ottomatic.domain.model.items.ImageResultItem
import com.example.ottomatic.engine.Action
import com.example.ottomatic.engine.ExecutionContext
import com.example.ottomatic.engine.NodeOutput
import com.example.ottomatic.engine.actionNode
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Which camera `action.camera_photo` uses.
 *
 * Two values and not a picker over camera ids: a multi-camera phone already hides its
 * physical sub-lenses behind one logical camera per direction, so there is nothing here for
 * a person to choose between that "front" and "back" do not already say.
 */
@Serializable
enum class CameraLens {
    @Label("Back camera")
    @SerialName("back")
    BACK,

    @Label("Front camera")
    @SerialName("front")
    FRONT,
}

/** Whether `action.camera_photo` fires the flash. */
@Serializable
enum class CameraFlash {
    @Label("Off")
    @SerialName("off")
    OFF,

    @Label("On")
    @SerialName("on")
    ON,

    @Label("Automatic")
    @SerialName("auto")
    AUTO,
}

/**
 * Config for `action.camera_photo`.
 *
 * **Blank folder means `DCIM/Ottomatic`**, resolved in the data layer rather than here, on
 * `ScreenshotConfig`'s rule: a default invented in the node would be a second answer that
 * could disagree with the facade's.
 *
 * **No picture-size field.** The sensor's own largest JPEG is written as it comes, which
 * costs nothing at run time — nothing on this path decodes the bytes, so a fifty-megapixel
 * photo is four megabytes rather than the two hundred a bitmap would be. Wanting a smaller
 * one is `action.image_edit` downstream, where the trade is visible on the canvas: the same
 * reason `action.screenshot` offers no format field.
 *
 * **The flash row is not offered for the front camera**, which on essentially every phone
 * has no flash unit. Offering it there would be a control that silently does nothing.
 */
@Serializable
data class CameraPhotoConfig(
    @Label("Camera")
    val lens: CameraLens = CameraLens.BACK,
    @Label("Flash")
    @VisibleWhen("lens", "back")
    val flash: CameraFlash = CameraFlash.OFF,
    @Label("Wait before the shot")
    @Hint("seconds")
    @Wired
    val delaySeconds: Int = 0,
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
 * `action.camera_photo` — takes a photograph with the phone's own camera.
 *
 * **The second image node that makes a picture rather than finding one**, after
 * `action.screenshot`, and the first that photographs the world rather than the screen. That
 * is what makes "when the door sensor opens, photograph the hall" and "when someone fails
 * the lock screen, take a selfie" expressible at all.
 *
 * **Headless, through camera2.** Ottomatic opens the camera itself: no camera app, no
 * Activity, nobody pressing a button. `ACTION_IMAGE_CAPTURE` was rejected for exactly the
 * property that makes it convenient in an editor — it hands the job to another app in the
 * foreground, so a macro built on it does nothing at all while the phone is in a pocket,
 * which is where this node is for. It also could not honour either field offered here: lens
 * choice is a hint most camera apps ignore, and a delay means nothing when a person is
 * pressing the shutter.
 *
 * **The delay is a camera setting, not a wait.** It is spent with the camera open and
 * metering, so it buys both time to get in frame and an exposed, white-balanced frame.
 * `action.delay` wired in front of this node would spend it with the camera shut and then
 * photograph with a sensor that has had no time to settle — which is why the field is here
 * and not there. It is capped at `ImageLimits.MAX_PHOTO_DELAY_SECONDS`, because a camera is
 * exclusive hardware and the wait is time during which nothing else on the phone can
 * photograph anything.
 *
 * **It takes the torch out.** Opening the camera revokes any torch `action.flashlight`
 * switched on, and it does not come back — restoring it would be a hidden side effect and
 * would fight a flash capture. The visible half of the same fact is that a macro wanting
 * light for the shot asks for it in this node's own flash field.
 *
 * **The engine is a foreground service, which Android does not simply let use a camera.**
 * From Android 11 the sensor follows the service's foreground-service *type*, so the type is
 * promoted to `specialUse|camera` for the seconds a photo takes and dropped again — see
 * `ServiceForeground` for why it cannot be claimed permanently. When the promotion is
 * refused the photo is still attempted and Android's own refusal is what gets reported; a
 * capture from a phone that has been idle in a pocket for hours is the case most likely to
 * be turned down, and that is a fact about Android rather than about the graph.
 *
 * **`trigger.image_saved` will fire on these**, because a photo in `DCIM/Ottomatic` is a new
 * picture whoever took it — `action.screenshot`'s consequence, with the same loop and the
 * same bounds if the two are wired together.
 *
 * **Never throws.** No camera access, no such lens, a camera another app is holding, a
 * platform refusal, a folder that cannot be written — all land on `state` with
 * `changed = false` and the reason in `error`, and `out` still pulses.
 *
 * It declares `CAMERA` and **nothing else**. A row this app creates needs no media grant to
 * write on any version, so `READ_MEDIA_IMAGES` would badge a working node — the reasoning
 * `action.screenshot` already carries, verbatim.
 */
class CameraPhotoAction : Action<CameraPhotoConfig, ImageResultItem> {

    override val definition = actionNode<CameraPhotoConfig, ImageResultItem>(
        typeId = "action.camera_photo",
        displayName = "Take Photo",
        description = "Takes a picture with the phone's front or back camera, " +
            "with flash and a delay before the shot",
        category = NodeCategory.IMAGES,
        icon = NodeIcon.CAMERA,
        output = dataOut<ImageResultItem>("state", label = "Result"),
        permissions = listOf(CAMERA_PERMISSION),
    )

    override suspend fun execute(
        input: CameraPhotoConfig,
        context: ExecutionContext,
    ): NodeOutput<ImageResultItem> {
        // Announced before the shot rather than reported after it: a flash that was asked
        // for and cannot happen is a degradation, and the photograph still lands.
        // `action.image_delete`'s rule about the missing bin, for its reason.
        if (input.flash != CameraFlash.OFF && !context.images.hasCameraFlash) {
            context.log(
                "This phone has no camera flash, so the photo was taken without one",
                LogLevel.WARN,
            )
        }
        val result = context.images.takePhoto(
            PhotoRequest(
                front = input.lens == CameraLens.FRONT,
                flash = input.flash.toFacade(),
                delaySeconds = input.delaySeconds,
                toFolder = input.toFolder.trim(),
                name = input.name.trim(),
                whenExists = input.whenExists.toWhenExists(),
            ),
        )
        return NodeOutput(context.reportImageWrite(result, "Photographed"))
    }

    private companion object {
        /**
         * Camera access, and the first node in this app to declare it.
         *
         * `Permissions.CAMERA` was held for the torch and declared by nobody, on the
         * grounds that nothing here ever opened a camera. This node does, and declaring it
         * is what puts the grant on the Permissions screen with a "Needed by" line, in the
         * Problems panel, and on this node's own config card.
         */
        val CAMERA_PERMISSION = PermissionRequirement(
            manifestPermission = Permissions.CAMERA.manifest,
            type = PrerequisiteType.RUNTIME,
            rationaleKey = "camera.photo",
        )
    }
}

private fun CameraFlash.toFacade(): FlashMode = when (this) {
    CameraFlash.OFF -> FlashMode.OFF
    CameraFlash.ON -> FlashMode.ON
    CameraFlash.AUTO -> FlashMode.AUTO
}
