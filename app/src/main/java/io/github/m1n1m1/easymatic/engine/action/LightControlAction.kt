package io.github.m1n1m1.easymatic.engine.action

import io.github.m1n1m1.easymatic.core.service.LightCommand
import io.github.m1n1m1.easymatic.core.service.LightOp
import io.github.m1n1m1.easymatic.core.service.LogLevel
import io.github.m1n1m1.easymatic.core.service.SmartHomeLimits
import io.github.m1n1m1.easymatic.core.service.SmartHomeTargetKind
import io.github.m1n1m1.easymatic.domain.model.HexColour
import io.github.m1n1m1.easymatic.domain.model.NodeCategory
import io.github.m1n1m1.easymatic.domain.model.NodeIcon
import io.github.m1n1m1.easymatic.domain.model.SmartHomeRef
import io.github.m1n1m1.easymatic.domain.model.config.Label
import io.github.m1n1m1.easymatic.domain.model.config.Picker
import io.github.m1n1m1.easymatic.domain.model.config.PickerKind
import io.github.m1n1m1.easymatic.domain.model.config.VisibleWhen
import io.github.m1n1m1.easymatic.domain.model.config.Wired
import io.github.m1n1m1.easymatic.domain.model.dataOut
import io.github.m1n1m1.easymatic.domain.model.items.LightChanged
import io.github.m1n1m1.easymatic.engine.Action
import io.github.m1n1m1.easymatic.engine.ExecutionContext
import io.github.m1n1m1.easymatic.engine.NodeOutput
import io.github.m1n1m1.easymatic.engine.actionNode
import kotlinx.serialization.Serializable

/**
 * Config for `action.light_control`.
 *
 * [target] is a [SmartHomeRef] spec and therefore carries its own hub — which is
 * why there is no hub field beside it and no `@MailFolder`-style sibling read. Two
 * fields could name a hub and a light that do not belong together, with nothing to
 * detect it; one spec makes that unrepresentable. The full argument is on
 * [SmartHomeRef].
 *
 * [colour] is a plain wired text field rather than a fifth
 * editable-with-a-chooser annotation. `#FF8800` is legible — a wrong one reads back
 * as wrong — which puts it on the `@WifiNetwork` side of the opacity line, and a
 * swatch grid does not clear that annotation's bar ("a chooser can only offer what
 * is reachable right now") because every colour always is. Read through
 * [HexColour], which also takes `#FA0`, a bare `FF8800` and a handful of names.
 *
 * [kelvin] rather than mireds: people know 2700 K is warm. The reciprocal is the
 * bridge's business and stops in `data/hue/`.
 *
 * [onlyLightsOn] is the opt-out of the behaviour the node's KDoc calls out below —
 * that setting a value also switches the light on. That default is right for "dim
 * the lamp to 30 %" and wrong for "warm the living room down for the evening",
 * which should not light four lamps nobody had switched on. It is offered for the
 * three value-setting operations only, because an explicit turn-on that skipped a
 * light for being off would do nothing at all.
 *
 * It reads as a room-and-zone setting and is deliberately **not restricted to
 * one**: "set the desk lamp to 30 %, but do not switch it on if it is off" is the
 * same request with one light in it, and a field that vanished when the target
 * happened to be a single bulb would be a rule with no reason anybody could see.
 */
@Serializable
data class LightControlConfig(
    @Label("Light or room") @Picker(PickerKind.LIGHT_TARGET) val target: String = "",
    @Label("What to do") val op: LightOp = LightOp.TURN_ON,
    @Label("Brightness %") @VisibleWhen("op", "SET_BRIGHTNESS") @Wired val brightness: Int = 100,
    @Label("Colour") @VisibleWhen("op", "SET_COLOUR") @Wired val colour: String = "#FFA757",
    @Label("Warmth (K)") @VisibleWhen("op", "SET_TEMPERATURE") @Wired val kelvin: Int = 2700,
    @Label("Only lights already on")
    @VisibleWhen("op", "SET_BRIGHTNESS", "SET_COLOUR", "SET_TEMPERATURE")
    val onlyLightsOn: Boolean = false,
    @Label("Fade time (ms)") val transitionMs: Int = SmartHomeLimits.DEFAULT_TRANSITION_MS,
)

/**
 * Action for `action.light_control`. Turns a light, room or zone on or off, toggles
 * it, or sets its brightness, colour or warmth.
 *
 * **One node rather than six**, which is `action.mail_update`'s argument used where
 * it holds: all six take the same target, produce the same receipt and pulse the
 * single `out`. What differs is one enum, which is what `@VisibleWhen` is for.
 * `action.light_scene` is a *separate* node not because it routes differently but
 * because its target comes from a different chooser — and a property may carry only
 * one `@Picker` (`checkWidgetAnnotations`), so no mode enum could ever switch it.
 *
 * **The typeId says `light`, not `hue`**, deliberately: a workflow persists this
 * string and an older schema is discarded rather than migrated, so it cannot be
 * renamed later. Nothing above `data/hue/` is Hue-shaped — the facade speaks
 * percent, sRGB and kelvin — so a second vendor's bulb would work through this node
 * under a name that lied. "Philips Hue" is in the description instead, which
 * `NodeSuggestions` searches.
 *
 * **Declares no permission**, deliberately: `INTERNET` is install-time, is already
 * in the manifest, and gets no `Permissions` constant, by the rule the manifest
 * states for `android.permission.NFC`. `action.send_mail` declares none for exactly
 * this reason. What can actually stop this node is a bridge that is unplugged or a
 * key it has forgotten, neither of which is a permission — both are reported on the
 * hub.
 *
 * Never throws. A blank target, an unparseable spec, a scene wired in where a light
 * belongs, an uninterpretable colour, a deleted hub, an unreachable bridge and a
 * certificate that stopped matching all land on `state` with `changed = false` and
 * the reason in `error`, and `out` still pulses.
 *
 * Two behaviours worth knowing rather than discovering:
 *  - **Setting a brightness also turns the light on.** A bridge will store a
 *    dimming level on a light that is off, light nothing, and report success —
 *    which reads exactly like a node that did nothing. `onlyLightsOn` is the
 *    deliberate opt-out.
 *  - **Toggle costs two round trips**, because the bridge has no toggle: the
 *    current state is read and the inverse written. It is therefore the one
 *    operation that can race somebody at the wall switch.
 *  - **`onlyLightsOn` costs a read and then one write per lit light**, because a
 *    single request to a room reaches every light in it and cannot say "except the
 *    ones that are off". A room of twelve lamps is one read and up to twelve
 *    writes, paced so the bridge does not silently drop the tail of them.
 *
 * `changed = false` with a **blank** error is not a failure and is the one thing
 * only this node can answer: nothing in the target was on, so nothing was written.
 * It is logged at INFO rather than WARN, because a macro that dims the living room
 * every evening should not file a warning on the evenings it is already dark.
 */
class LightControlAction : Action<LightControlConfig, LightChanged> {

    override val definition = actionNode<LightControlConfig, LightChanged>(
        typeId = "action.light_control",
        displayName = "Control Light",
        description = "Turns a light, room or zone on or off, or sets its brightness, colour or warmth — Philips Hue",
        category = NodeCategory.SMART_HOME,
        icon = NodeIcon.LIGHTBULB,
        output = dataOut<LightChanged>("state"),
    )

    override suspend fun execute(input: LightControlConfig, context: ExecutionContext): NodeOutput<LightChanged> {
        val parsed = SmartHomeRef.parse(input.target)
        val rgb = if (input.op == LightOp.SET_COLOUR) HexColour.parse(input.colour) else 0
        val problem = when {
            parsed == null && input.target.isBlank() -> "No light chosen"
            // Names what it read rather than only that it failed: a spec that
            // arrived through a variable is exactly the case where seeing the
            // string is what explains it.
            parsed == null -> "Not a light reference: \"${input.target.trim()}\""
            parsed.kind == SmartHomeTargetKind.SCENE -> "That is a scene, not a light — use Activate Scene"
            rgb == null -> "Not a colour: \"${input.colour.trim()}\""
            else -> null
        }
        if (parsed == null || problem != null) {
            context.log(problem.orEmpty(), LogLevel.ERROR)
            return NodeOutput(LightChanged(input.target, input.op.name, changed = false, error = problem.orEmpty()))
        }
        val result = context.smartHome.apply(
            LightCommand(
                hubId = parsed.hubId,
                kind = parsed.kind,
                rid = parsed.rid,
                op = input.op,
                brightnessPercent = input.brightness.coerceIn(0, PERCENT_MAX),
                colourRgb = rgb ?: LightCommand.NO_COLOUR,
                kelvin = input.kelvin,
                transitionMs = input.transitionMs.coerceIn(0, SmartHomeLimits.MAX_TRANSITION_MS),
                onlyIfOn = input.onlyLightsOn,
            ),
        )
        if (!result.changed) {
            // A blank error means the hub was reached and had nothing to change —
            // "only lights already on", with none of them on. Not a warning.
            if (result.error.isBlank()) {
                context.log("Nothing in \"${parsed.name}\" was on, so nothing changed")
            } else {
                context.log(result.error, LogLevel.WARN)
            }
        }
        return NodeOutput(LightChanged(input.target, input.op.name, result.changed, result.error))
    }

    private companion object {
        const val PERCENT_MAX = 100
    }
}
