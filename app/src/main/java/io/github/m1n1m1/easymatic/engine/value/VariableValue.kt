package io.github.m1n1m1.easymatic.engine.value

import io.github.m1n1m1.easymatic.core.model.PortName
import io.github.m1n1m1.easymatic.domain.model.Direction
import io.github.m1n1m1.easymatic.domain.model.NodeCategory
import io.github.m1n1m1.easymatic.domain.model.NodeIcon
import io.github.m1n1m1.easymatic.domain.model.Port
import io.github.m1n1m1.easymatic.domain.model.PortKind
import io.github.m1n1m1.easymatic.domain.model.config.Label
import io.github.m1n1m1.easymatic.domain.model.config.Picker
import io.github.m1n1m1.easymatic.domain.model.config.PickerKind
import io.github.m1n1m1.easymatic.domain.model.schema.Item
import io.github.m1n1m1.easymatic.domain.model.schema.ItemSchema
import io.github.m1n1m1.easymatic.engine.BoundVariables
import io.github.m1n1m1.easymatic.engine.ExecutionContext
import io.github.m1n1m1.easymatic.engine.RawValue
import io.github.m1n1m1.easymatic.engine.adaptiveValueNode
import kotlinx.serialization.Serializable

/**
 * Config for `value.variable`. Not `@Wired`: a value node is a leaf and may
 * declare no data inputs at all, which `NodeDeclarationContractTest` enforces.
 *
 * The property holds a [io.github.m1n1m1.easymatic.domain.model.VariableRef] spec chosen
 * from the picker — an id, not a name.
 */
@Serializable
data class VariableValueConfig(
    @Label("Variable") @Picker(PickerKind.VARIABLE) val name: String = "",
)

/** The one DATA output, declared as a wildcard and retyped by `effectivePorts`. */
private val VARIABLE_OUT = Port(
    name = PortName("value"),
    kind = PortKind.DATA,
    direction = Direction.OUT,
    schema = ItemSchema.Wildcard,
    label = "Value",
)

/**
 * `value.variable` — what a variable holds right now.
 *
 * The read half of `action.set_variable`, and the reason that action is worth
 * having: a stored value nobody can look at is a write-only log. Being a value
 * node it is *pulled* just before whoever needs it, so "if the counter is over
 * five" is one comparison rather than a fetch step wired ahead of one.
 *
 * The only value node with configuration. That is what a variable needs and what
 * the other sixteen do not — there is one battery level, but as many variables as
 * the user cares to declare. It stays within the purity contract regardless: no
 * exec ports, no data inputs, no permission, and a read that cannot fail.
 *
 * Reads as unset (null) rather than as empty text when nothing has written it *and*
 * its declaration states no initial value, so a comparison against a variable
 * nothing has touched fails closed instead of matching `""`.
 *
 * **It is typed, and it is the one place a conversion happens off-canvas.** The
 * output port carries the declaration's
 * [io.github.m1n1m1.easymatic.domain.model.config.ValueType], so a counter drops
 * straight into a numeric port with no `transform.convert` in the wire — and
 * `ValueType.convert` is applied here without a node on screen to show it. That is
 * safe for the reason the totality rule asks for: **the declaration is the visible
 * artefact.** The user chose the type in the variables editor, the port on the card
 * wears that type's colour, and the write side is type-checked too, so a stored
 * value of the wrong shape can only come from a declaration retyped after the fact
 * — which `convert` renders as the type's zero rather than throwing.
 */
class VariableValue : RawValue<VariableValueConfig> {

    override val definition = adaptiveValueNode<VariableValueConfig>(
        typeId = "value.variable",
        displayName = "Variable",
        description = "The current value of a variable",
        category = NodeCategory.VALUE_VARIABLES,
        icon = NodeIcon.VARIABLE,
        output = VARIABLE_OUT,
    )

    @Suppress("ReturnCount") // Unset, unbound, declared — three answers, three exits.
    override suspend fun readItem(config: VariableValueConfig, context: ExecutionContext): Item? {
        val raw = context.variables.get(config.name) ?: return null
        // Outside a workflow-bound context — an engine-only test, a preview — there
        // are no declarations to consult, so the value reads as the flat text it is
        // rather than the node failing to answer at all.
        val declaration = (context.variables as? BoundVariables)?.declarationOf(config.name)
            ?: return Item(raw, TEXT_SCHEMA)
        return declaration.type.convert(Item(raw, TEXT_SCHEMA))
    }
}

/** What a stored value is before its declaration says otherwise. */
private val TEXT_SCHEMA = ItemSchema.Primitive(String::class)
