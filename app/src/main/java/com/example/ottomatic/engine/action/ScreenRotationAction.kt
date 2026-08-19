package com.example.ottomatic.engine.action

import com.example.ottomatic.core.service.ScreenRotation
import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.NodeIcon
import com.example.ottomatic.domain.model.config.Label
import com.example.ottomatic.domain.model.dataOut
import com.example.ottomatic.domain.model.items.ScreenRotationState
import com.example.ottomatic.engine.Action
import com.example.ottomatic.engine.ExecutionContext
import com.example.ottomatic.engine.NodeOutput
import com.example.ottomatic.engine.actionNode
import kotlinx.serialization.Serializable

/** Config for `action.screen_rotation`. */
@Serializable
data class ScreenRotationConfig(
    @Label("Rotation") val rotation: ScreenRotation = ScreenRotation.PORTRAIT,
)

/**
 * Action for `action.screen_rotation`. Turns the screen to one of the four
 * positions a display can be locked to and holds it there, reporting the
 * resulting [ScreenRotationState] on its `state` data port. Requires
 * `WRITE_SETTINGS`.
 *
 * **It turns auto-rotation off**, because the chosen rotation is only honoured
 * while it is — see [com.example.ottomatic.core.service.SystemServices.setScreenRotation].
 * That is the whole reason this is a second node rather than another field on
 * `action.auto_rotate`: the two are opposite requests, and giving the device back
 * to the accelerometer is that node turning the toggle on again.
 *
 * Why four choices and not the six `trigger.device_orientation` reports is
 * [ScreenRotation]'s to explain: a phone lying face down on a desk still shows
 * portrait or landscape, so there is no rotation to ask it for.
 */
class ScreenRotationAction : Action<ScreenRotationConfig, ScreenRotationState> {

    override val definition = actionNode<ScreenRotationConfig, ScreenRotationState>(
        typeId = "action.screen_rotation",
        displayName = "Set Screen Rotation",
        description = "Turns the screen to portrait or landscape and stops it rotating on its own",
        category = NodeCategory.DEVICE_SETTINGS,
        icon = NodeIcon.ORIENTATION,
        output = dataOut<ScreenRotationState>("state"),
    )

    override suspend fun execute(
        input: ScreenRotationConfig,
        context: ExecutionContext,
    ): NodeOutput<ScreenRotationState> {
        val result = context.systemServices.setScreenRotation(input.rotation)
        return NodeOutput(
            ScreenRotationState(
                rotation = result?.rotation ?: input.rotation,
                changed = result?.changed ?: false,
            ),
        )
    }
}
