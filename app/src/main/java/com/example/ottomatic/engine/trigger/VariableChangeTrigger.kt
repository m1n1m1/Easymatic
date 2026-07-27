package com.example.ottomatic.engine.trigger

import com.example.ottomatic.core.trigger.TriggerSource
import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.NodeIcon
import com.example.ottomatic.domain.model.WorkflowNode
import com.example.ottomatic.domain.model.config.Label
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
 * Config for `trigger.variable_change`. The name was previously read without
 * being declared, so the trigger silently never fired until the config map was
 * edited by hand.
 */
@Serializable
data class VariableChangeConfig(
    @Label("Variable name") val name: String = "",
)

/**
 * Trigger for `trigger.variable_change`. Fires when the named variable changes
 * value. Subscribes to variable-change events via [TriggerHost.variableChanges].
 *
 * Produces a typed [VariableChange] item on the `variable` data port.
 *
 * Payload contract with the host:
 * - `name` — the variable name
 * - `value` — the new string value
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
