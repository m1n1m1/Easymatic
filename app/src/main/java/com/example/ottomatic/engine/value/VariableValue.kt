package com.example.ottomatic.engine.value

import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.NodeIcon
import com.example.ottomatic.domain.model.config.Label
import com.example.ottomatic.domain.model.dataOut
import com.example.ottomatic.engine.ExecutionContext
import com.example.ottomatic.engine.ValueNode
import com.example.ottomatic.engine.valueNode
import kotlinx.serialization.Serializable

/**
 * Config for `value.variable`. Not `@Wired`: a value node is a leaf and may
 * declare no data inputs at all, which `NodeDeclarationContractTest` enforces.
 */
@Serializable
data class VariableValueConfig(
    @Label("Variable name") val name: String = "",
)

/**
 * `value.variable` — what a named variable holds right now.
 *
 * The read half of `action.set_variable`, and the reason that action is worth
 * having: a stored value nobody can look at is a write-only log. Being a value
 * node it is *pulled* just before whoever needs it, so "if the counter is over
 * five" is one comparison rather than a fetch step wired ahead of one.
 *
 * The first value node with configuration. That is what a variable needs and
 * what the other fifteen do not — there is one battery level, but there are as
 * many variables as the user cares to name. It stays within the purity contract
 * regardless: no exec ports, no data inputs, no permission, and a read that
 * cannot fail.
 *
 * Reads as unset (null) rather than as empty text when nothing has written it,
 * so a comparison against a variable that does not exist yet fails closed
 * instead of matching `""`.
 *
 * Text, always. A counter compared as a number goes through `action.if`'s type
 * chooser, and one wired into a numeric port picks up a visible
 * `transform.convert` — the same route every other loosely-typed value takes.
 */
class VariableValue : ValueNode<VariableValueConfig, String> {

    override val definition = valueNode<VariableValueConfig, String>(
        typeId = "value.variable",
        displayName = "Variable",
        description = "The current value of a named variable",
        category = NodeCategory.VALUE_VARIABLES,
        icon = NodeIcon.VARIABLE,
        output = dataOut<String>("value", label = "Value"),
    )

    override suspend fun read(config: VariableValueConfig, context: ExecutionContext): String? =
        config.name.trim().takeIf { it.isNotEmpty() }?.let { context.variables.get(it) }
}
