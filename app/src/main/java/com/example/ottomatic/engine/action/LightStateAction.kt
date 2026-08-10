package com.example.ottomatic.engine.action

import com.example.ottomatic.core.service.LightCommand
import com.example.ottomatic.core.service.LightRead
import com.example.ottomatic.core.service.LogLevel
import com.example.ottomatic.core.service.SmartHomeTargetKind
import com.example.ottomatic.domain.model.HexColour
import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.NodeIcon
import com.example.ottomatic.domain.model.SmartHomeRef
import com.example.ottomatic.domain.model.config.Label
import com.example.ottomatic.domain.model.config.Picker
import com.example.ottomatic.domain.model.config.PickerKind
import com.example.ottomatic.domain.model.dataOut
import com.example.ottomatic.domain.model.items.LightState
import com.example.ottomatic.engine.Action
import com.example.ottomatic.engine.ExecutionContext
import com.example.ottomatic.engine.NodeOutput
import com.example.ottomatic.engine.actionNode
import kotlinx.serialization.Serializable

/** Config for `action.light_state`. */
@Serializable
data class LightStateConfig(
    @Label("Light or room") @Picker(PickerKind.LIGHT_TARGET) val target: String = "",
)

/**
 * Action for `action.light_state`. Reads one light's or group's current state.
 *
 * **An action and not a `value.light_state`**, which is the one place this family
 * argues with CLAUDE.md's "every trigger over a readable state gets a value node
 * too" — and it argues using that rule's own escape clause: *reading it is expensive
 * or failable, which is an action's job instead*. This is a round trip to a device
 * on the LAN that may be unplugged, so it is both of the things the pull side may
 * not be. There is also no trigger to pair with: nothing here watches a bridge.
 * "If the hall light is already on, …" is this node feeding an `action.if`, on the
 * exec wire where the latency is visible — the shape CLAUDE.md prescribes for "how
 * many unread do I have?".
 *
 * **A separate node from `action.light_control`** because a node declares exactly
 * one DATA output and this one's is a different type. Folding it in would mean an
 * adaptive node retyping its output port from an enum, which is machinery reserved
 * for nodes whose ports come from the graph.
 *
 * Never throws. A blank target, a bad spec, a scene wired in, a deleted hub and an
 * unreachable bridge all land on `state` with `found = false` and the reason in
 * `error`, and `out` still pulses. That is why [LightState.found] exists at all:
 * without it, "the light is off" and "the bridge was unplugged" would be the same
 * answer to an `action.if`.
 */
class LightStateAction : Action<LightStateConfig, LightState> {

    override val definition = actionNode<LightStateConfig, LightState>(
        typeId = "action.light_state",
        displayName = "Get Light State",
        description = "Reads whether a light is on, how bright it is and what colour — Philips Hue",
        category = NodeCategory.SMART_HOME,
        icon = NodeIcon.LIGHTBULB,
        output = dataOut<LightState>("state"),
    )

    override suspend fun execute(input: LightStateConfig, context: ExecutionContext): NodeOutput<LightState> {
        val parsed = SmartHomeRef.parse(input.target)
        val problem = when {
            parsed == null && input.target.isBlank() -> "No light chosen"
            parsed == null -> "Not a light reference: \"${input.target.trim()}\""
            parsed.kind == SmartHomeTargetKind.SCENE -> "A scene has no state to read — choose a light or a room"
            else -> null
        }
        if (parsed == null || problem != null) {
            context.log(problem.orEmpty(), LogLevel.ERROR)
            return NodeOutput(LightState(target = input.target, error = problem.orEmpty()))
        }
        val reading = context.smartHome.read(LightRead(parsed.hubId, parsed.kind, parsed.rid))
        if (!reading.found) context.log(reading.error, LogLevel.WARN)
        return NodeOutput(
            LightState(
                target = input.target,
                name = reading.name.ifBlank { parsed.name },
                on = reading.on,
                brightness = reading.brightnessPercent,
                // Blank rather than "#000000" for a bulb with no gamut: black is a
                // colour, and "this light has none" is not one.
                colour = reading.colourRgb.takeIf { it != LightCommand.NO_COLOUR }?.let(HexColour::format).orEmpty(),
                kelvin = reading.kelvin,
                reachable = reading.reachable,
                found = reading.found,
                error = reading.error,
            ),
        )
    }
}
