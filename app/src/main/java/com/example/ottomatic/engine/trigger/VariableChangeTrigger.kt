package com.example.ottomatic.engine.trigger

import com.example.ottomatic.core.trigger.TriggerSource
import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.NodeIcon
import com.example.ottomatic.domain.model.WorkflowNode
import com.example.ottomatic.domain.model.config.Label
import com.example.ottomatic.domain.model.config.Picker
import com.example.ottomatic.domain.model.config.PickerKind
import com.example.ottomatic.domain.model.dataOut
import com.example.ottomatic.domain.model.items.VariableChange
import com.example.ottomatic.domain.model.schema.DateTime
import com.example.ottomatic.engine.NodeOutput
import com.example.ottomatic.engine.triggerNode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable

/**
 * Config for `trigger.variable_change`. Holds a
 * [com.example.ottomatic.domain.model.VariableRef] spec chosen from the picker.
 */
@Serializable
data class VariableChangeConfig(
    @Label("Variable") @Picker(PickerKind.VARIABLE) val name: String = "",
)

/**
 * Trigger for `trigger.variable_change`. Fires when the chosen variable changes
 * value. Subscribes to variable-change events via [TriggerHost.variableChanges].
 *
 * Produces a typed [VariableChange] item on the `variable` data port.
 *
 * Payload contract with the host:
 * - `name` — the variable's *name*, not the ref it was armed with
 * - `value` — the new string value
 *
 * The ref goes in and a name comes out because the host is wrapped, per arm, in a
 * [BoundTriggerHost] that resolves the scope on the way in and the declaration on
 * the way out. This node therefore hands its config straight through and never
 * learns that scopes exist — and a store key can never leak into a notification.
 *
 * The `value` field stays text. Retyping one field of a trigger's `@Serializable`
 * output struct is a different mechanism from retyping a whole port, and a
 * comparison against the new value has `action.if`'s type chooser already.
 */
class VariableChangeTrigger : Trigger<VariableChangeConfig, VariableChange> {

    override val definition = triggerNode<VariableChangeConfig, VariableChange>(
        typeId = "trigger.variable_change",
        displayName = "Variable Change",
        description = "Fires when a named variable changes value",
        category = NodeCategory.VARIABLES,
        icon = NodeIcon.BOLT,
        output = dataOut<VariableChange>("variable", label = "Variable"),
    )

    override fun activate(
        config: VariableChangeConfig,
        node: WorkflowNode,
        host: TriggerHost,
    ): Flow<NodeOutput<VariableChange>> {
        val name = config.name.takeIf { it.isNotBlank() } ?: return emptyFlow()
        return host.variableChanges(name)
            .filter { it.source == TriggerSource.VARIABLE }
            .map { bus ->
                NodeOutput(
                    VariableChange(
                        name = bus.payload[KEY_NAME].orEmpty(),
                        value = bus.payload[KEY_VALUE].orEmpty(),
                        timestamp = DateTime(bus.firedAtEpochMs),
                    ),
                )
            }
    }

    companion object {
        const val KEY_NAME = "name"
        const val KEY_VALUE = "value"
    }
}
