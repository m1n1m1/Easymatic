package com.example.ottomatic.engine.trigger

import com.example.ottomatic.core.trigger.TriggerSource
import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.WorkflowNode
import com.example.ottomatic.domain.model.dataOut
import com.example.ottomatic.domain.model.items.ModeChange
import com.example.ottomatic.domain.model.schema.Item
import com.example.ottomatic.engine.NodeOutput
import com.example.ottomatic.engine.triggerNode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map

/**
 * Trigger for `trigger.mode_change`. Fires when the device's UI / night mode
 * changes (e.g. dark theme toggled). Subscribes to app-lifecycle events via
 * [TriggerHost.appLifecycleEvents].
 *
 * Produces a typed [ModeChange] item on the `mode` data port.
 *
 * Payload contract with the host:
 * - `event` == `"mode"`
 * - `mode` ∈ `"normal"`, `"night"`
 */
class ModeChangeTrigger : Trigger<ModeChange> {

    override val definition = triggerNode<ModeChange>(
        typeId = "trigger.mode_change",
        displayName = "Dark Theme Change",
        description = "Fires when the device's UI / night mode changes",
        category = NodeCategory.DEVICE_STATE,
        iconKey = "bolt",
        dataOutputs = listOf(dataOut<ModeChange>("mode")),
        encodeData = { mode -> mapOf("mode" to Item.of(mode)) },
    )

    override fun activate(node: WorkflowNode, host: TriggerHost): Flow<NodeOutput<ModeChange>> =
        host.appLifecycleEvents()
            .filter { it.source == TriggerSource.APP }
            .filter { it.payload[KEY_EVENT] == EVENT_MODE }
            .map { bus ->
                NodeOutput(
                    ModeChange(
                        mode = bus.payload[KEY_MODE].orEmpty(),
                        timestamp = bus.firedAtEpochMs,
                    ),
                )
            }

    companion object {
        const val KEY_EVENT = "event"
        const val KEY_MODE = "mode"
        const val EVENT_MODE = "mode"
    }
}
