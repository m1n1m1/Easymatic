package com.example.ottomatic.engine.trigger

import com.example.ottomatic.core.trigger.TriggerSource
import com.example.ottomatic.domain.model.WorkflowNode
import com.example.ottomatic.domain.model.items.VariableChange
import com.example.ottomatic.domain.model.schema.Item
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map

/**
 * Trigger for `trigger.variable_change`. Fires when the named variable
 * changes value. The variable name is taken from the node's `name` config
 * key. Subscribes to variable-change events via
 * [TriggerHost.variableChanges].
 *
 * Produces a typed [VariableChange] item on the `variable` data port.
 *
 * Payload contract with the host:
 * - `name` — the variable name
 * - `value` — the new string value
 */
class VariableChangeTrigger : Trigger {

    override val typeId: String = TYPE_ID

    override fun activate(node: WorkflowNode, host: TriggerHost): Flow<TriggerEvent> {
        val name = node.config[CONFIG_NAME]?.takeIf { it.isNotBlank() } ?: return kotlinx.coroutines.flow.emptyFlow()
        return host.variableChanges(name)
            .filter { it.source == TriggerSource.VARIABLE }
            .map { bus ->
                TriggerEvent(
                    triggerNodeId = node.id,
                    dataOut = mapOf(
                        "variable" to Item.of(
                            VariableChange(
                                name = bus.payload[KEY_NAME].orEmpty(),
                                value = bus.payload[KEY_VALUE].orEmpty(),
                                timestamp = bus.firedAtEpochMs,
                            ),
                        ),
                    ),
                )
            }
    }

    companion object {
        const val TYPE_ID = "trigger.variable_change"

        const val CONFIG_NAME = "name"

        const val KEY_NAME = "name"
        const val KEY_VALUE = "value"
    }
}
