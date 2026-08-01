package com.example.ottomatic.engine.action

import com.example.ottomatic.core.service.LogLevel
import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.NodeIcon
import com.example.ottomatic.domain.model.config.Label
import com.example.ottomatic.domain.model.config.Wired
import com.example.ottomatic.engine.Action
import com.example.ottomatic.engine.ExecutionContext
import com.example.ottomatic.engine.NodeOutput
import com.example.ottomatic.engine.effectNode
import kotlinx.serialization.Serializable

/**
 * Config for `action.set_variable`.
 *
 * [value] is `@Wired` and [name] is not, on purpose. The value is the thing that
 * comes from upstream — a script's result, a sensor reading, a broken-out field.
 * The name is what the *macro* calls it, and a variable whose name came down a
 * wire could not be found by `value.variable` or armed by
 * `trigger.variable_change`, both of which need to know it while the graph is
 * being edited rather than while it runs.
 */
@Serializable
data class SetVariableConfig(
    @Label("Variable name") val name: String = "",
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
 * A blank name is ignored rather than writing to `""` — a half-configured node
 * should do nothing, not quietly accumulate a variable nobody can find.
 */
class SetVariableAction : Action<SetVariableConfig, Unit> {

    override val definition = effectNode<SetVariableConfig>(
        typeId = "action.set_variable",
        displayName = "Set Variable",
        description = "Remembers a value under a name, readable by later runs and other macros",
        category = NodeCategory.DATA,
        icon = NodeIcon.VARIABLE,
    )

    override suspend fun execute(input: SetVariableConfig, context: ExecutionContext): NodeOutput<Unit> {
        val name = input.name.trim()
        if (name.isEmpty()) {
            context.log("Set Variable: no name configured, nothing stored", LogLevel.WARN)
            return NodeOutput(Unit)
        }
        context.variables.set(name, input.value)
        context.log("Set Variable: $name = ${input.value}")
        return NodeOutput(Unit)
    }
}
