package io.github.m1n1m1.easymatic.engine.action

import io.github.m1n1m1.easymatic.core.service.LogLevel
import io.github.m1n1m1.easymatic.core.service.SceneOp
import io.github.m1n1m1.easymatic.core.service.SceneRecall
import io.github.m1n1m1.easymatic.core.service.SmartHomeLimits
import io.github.m1n1m1.easymatic.core.service.SmartHomeTargetKind
import io.github.m1n1m1.easymatic.domain.model.NodeCategory
import io.github.m1n1m1.easymatic.domain.model.NodeIcon
import io.github.m1n1m1.easymatic.domain.model.SmartHomeRef
import io.github.m1n1m1.easymatic.domain.model.config.Label
import io.github.m1n1m1.easymatic.domain.model.config.Picker
import io.github.m1n1m1.easymatic.domain.model.config.PickerKind
import io.github.m1n1m1.easymatic.domain.model.dataOut
import io.github.m1n1m1.easymatic.domain.model.items.SceneChanged
import io.github.m1n1m1.easymatic.engine.Action
import io.github.m1n1m1.easymatic.engine.ExecutionContext
import io.github.m1n1m1.easymatic.engine.NodeOutput
import io.github.m1n1m1.easymatic.engine.actionNode
import kotlinx.serialization.Serializable

/**
 * Config for `action.light_scene`.
 *
 * [scene] is a [SmartHomeRef] spec, as `action.light_control`'s target is, and it
 * carries its own hub for the same reason.
 *
 * [op] takes no `@VisibleWhen` fields with it, unlike Control Light's: all three
 * operations act on the one thing the user chose, and the group that
 * [SceneOp.TURN_OFF] switches is found *from* the scene rather than asked for. A
 * second picker for "and which room?" would be asking for something the answer
 * already contains.
 */
@Serializable
data class LightSceneConfig(
    @Label("Scene") @Picker(PickerKind.LIGHT_SCENE) val scene: String = "",
    @Label("What to do") val op: SceneOp = SceneOp.ACTIVATE,
    @Label("Fade time (ms)") val transitionMs: Int = SmartHomeLimits.DEFAULT_TRANSITION_MS,
)

/**
 * Action for `action.light_scene`. Activates a scene set up in the vendor's own app,
 * turns the room it belongs to off, or toggles between the two.
 *
 * **A separate node from `action.light_control` rather than a seventh operation on
 * it**, and the reason is the declared ports rather than the exec routing: a scene
 * and a light are chosen from two different choosers, `checkWidgetAnnotations`
 * allows a property only one `@Picker`, and a `PickerField` receives only its kind
 * — so a mode enum could never switch which list opens. That is the same split
 * `APP` and `APP_FILTER` already have. The operations *within* this node are one
 * node for `action.mail_update`'s reason: same target, same receipt, one pulse.
 *
 * **A scene has no off**, so [SceneOp.TURN_OFF] and half of [SceneOp.TOGGLE] act on
 * the room or zone behind the scene, which `data/hue/` finds from the scene itself.
 * Two things follow that are worth knowing rather than discovering:
 *  - **Off is wider than on.** Recalling a scene lights whatever lamps that scene
 *    names; turning it off switches off *the whole room*, including a lamp the
 *    scene never touched. That is what "turn the scene off" means to a person, and
 *    it is also the only thing the bridge can be asked for.
 *  - **Toggle costs an extra read** — it asks the group whether it is on — so it is
 *    the one operation here that can race somebody at the wall switch.
 *
 * **Scenes are not created here.** A scene is a saved arrangement of many lights,
 * built where that arrangement can be seen; this node's job is to recall one at
 * seven in the morning. That division is why there is no "save current state as a
 * scene" action — an automation app has nowhere useful to preview it.
 *
 * Declares no permission, and never throws, on `action.light_control`'s reasoning.
 */
class LightSceneAction : Action<LightSceneConfig, SceneChanged> {

    override val definition = actionNode<LightSceneConfig, SceneChanged>(
        typeId = "action.light_scene",
        displayName = "Control Scene",
        description = "Activates a lighting scene you set up in the Hue app, or turns its room off — Philips Hue",
        category = NodeCategory.SMART_HOME,
        icon = NodeIcon.SCENE,
        output = dataOut<SceneChanged>("state"),
    )

    override suspend fun execute(input: LightSceneConfig, context: ExecutionContext): NodeOutput<SceneChanged> {
        val parsed = SmartHomeRef.parse(input.scene)
        val problem = when {
            parsed == null && input.scene.isBlank() -> "No scene chosen"
            parsed == null -> "Not a scene reference: \"${input.scene.trim()}\""
            parsed.kind != SmartHomeTargetKind.SCENE -> "That is a light, not a scene — use Control Light"
            else -> null
        }
        if (parsed == null || problem != null) {
            context.log(problem.orEmpty(), LogLevel.ERROR)
            return NodeOutput(SceneChanged(input.scene, input.op.name, changed = false, error = problem.orEmpty()))
        }
        val result = context.smartHome.recall(
            SceneRecall(
                hubId = parsed.hubId,
                rid = parsed.rid,
                op = input.op,
                transitionMs = input.transitionMs.coerceIn(0, SmartHomeLimits.MAX_TRANSITION_MS),
            ),
        )
        if (!result.changed) context.log(result.error, LogLevel.WARN)
        return NodeOutput(SceneChanged(input.scene, input.op.name, result.changed, result.error))
    }
}
