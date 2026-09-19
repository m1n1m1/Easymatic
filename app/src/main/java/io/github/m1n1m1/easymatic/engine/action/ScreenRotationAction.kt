package io.github.m1n1m1.easymatic.engine.action

import io.github.m1n1m1.easymatic.core.service.LogLevel
import io.github.m1n1m1.easymatic.core.service.ScreenRotation
import io.github.m1n1m1.easymatic.domain.model.NodeCategory
import io.github.m1n1m1.easymatic.domain.model.NodeIcon
import io.github.m1n1m1.easymatic.domain.model.config.Label
import io.github.m1n1m1.easymatic.domain.model.dataOut
import io.github.m1n1m1.easymatic.domain.model.items.ScreenRotationState
import io.github.m1n1m1.easymatic.engine.Action
import io.github.m1n1m1.easymatic.engine.ExecutionContext
import io.github.m1n1m1.easymatic.engine.NodeOutput
import io.github.m1n1m1.easymatic.engine.actionNode
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
 * while it is — see [io.github.m1n1m1.easymatic.core.service.SystemServices.setScreenRotation].
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
        permissions = listOf(WRITE_SETTINGS_PERMISSION),
        output = dataOut<ScreenRotationState>("state"),
    )

    override suspend fun execute(
        input: ScreenRotationConfig,
        context: ExecutionContext,
    ): NodeOutput<ScreenRotationState> {
        val result = context.systemServices.setScreenRotation(input.rotation)
        if (result?.changed != true) {
            context.log(
                "ScreenRotation: no device setting change was reported; check permissions and device restrictions",
                LogLevel.WARN,
            )
        }
        return NodeOutput(
            ScreenRotationState(
                rotation = result?.rotation ?: input.rotation,
                changed = result?.changed ?: false,
            ),
        )
    }
}
