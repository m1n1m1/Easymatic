package io.github.m1n1m1.easymatic.engine.action

import io.github.m1n1m1.easymatic.domain.model.NodeCategory
import io.github.m1n1m1.easymatic.domain.model.NodeIcon
import io.github.m1n1m1.easymatic.domain.model.config.Label
import io.github.m1n1m1.easymatic.domain.model.config.Picker
import io.github.m1n1m1.easymatic.domain.model.config.PickerKind
import io.github.m1n1m1.easymatic.domain.model.config.Wired
import io.github.m1n1m1.easymatic.engine.Action
import io.github.m1n1m1.easymatic.engine.ExecutionContext
import io.github.m1n1m1.easymatic.engine.NodeOutput
import io.github.m1n1m1.easymatic.engine.effectNode
import kotlinx.serialization.Serializable

/**
 * Config for `action.set_variable`.
 *
 * [value] is `@Wired` and [name] is not, on purpose. The value is the thing that
 * comes from upstream — a script's result, a sensor reading, a broken-out field.
 * The name is which variable the *macro* means, and one that came down a wire
 * could not be found by `value.variable` or armed by `trigger.variable_change`,
 * both of which need to know it while the graph is being edited rather than while
 * it runs.
 *
 * It holds a [io.github.m1n1m1.easymatic.domain.model.VariableRef] spec chosen from the
 * picker, never a typed name — see [PickerKind.VARIABLE].
 */
@Serializable
data class SetVariableConfig(
    @Label("Variable") @Picker(PickerKind.VARIABLE) val name: String = "",
    @Label("Value") @Wired val value: String = "",
)

/**
 * `action.set_variable` — remembers a value under a name, for later runs.
 *
 * The graph's only writable state. Everything else here is derived from what is
 * true right now: a trigger fires, values are pulled, actions run, and nothing
 * survives. That is enough for "when I get home, turn the lights on" and not
 * enough for "the third time this happens today" — and no amount of scripting
 * closes the gap, because `action.script` gets a fresh isolate every run and
 * cannot remember its own previous answer either.
 *
 * Writing the same value again is a deliberate no-op: `trigger.variable_change`
 * means "when this changes", so a macro that rewrites a variable on a timer must
 * not fire it on every tick.
 *
 * The node is adaptive so its `value` port can take the *declared* type of the
 * variable it writes: a number wired at a text variable is then a refused drop
 * carrying a visible `transform.convert`, rather than a silent flattening. That
 * types the **connection**, not the storage — what lands in the store is still the
 * flat text `NodeSchema.decode` produced through `asText()`.
 *
 * A write that is refused — nothing chosen, a declaration since deleted, or a
 * constant — logs at WARN and still pulses `out`. It is a runtime disappointment,
 * not a structurally broken graph, and stopping the macro dead would be a far
 * bigger surprise than one step that says it did nothing.
 */
class SetVariableAction : Action<SetVariableConfig, Unit> {

    override val definition = effectNode<SetVariableConfig>(
        typeId = "action.set_variable",
        displayName = "Set Variable",
        description = "Remembers a value in a variable, readable by later runs",
        category = NodeCategory.DATA,
        icon = NodeIcon.VARIABLE,
        hasDynamicPorts = true,
    )

    override suspend fun execute(input: SetVariableConfig, context: ExecutionContext): NodeOutput<Unit> {
        val result = context.variables.set(input.name, input.value)
        if (!context.reportRefusal(NODE, input.name, result)) {
            context.log("$NODE: ${context.variableName(input.name)} = ${input.value}")
        }
        return NodeOutput(Unit)
    }

    private companion object {
        const val NODE = "Set Variable"
    }
}
