package com.example.ottomatic.engine.trigger

import com.example.ottomatic.core.trigger.TriggerSource
import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.NodeIcon
import com.example.ottomatic.domain.model.WorkflowNode
import com.example.ottomatic.domain.model.config.NoConfig
import com.example.ottomatic.domain.model.dataOut
import com.example.ottomatic.domain.model.items.ModeChange
import com.example.ottomatic.domain.model.schema.DateTime
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
class ModeChangeTrigger : Trigger<NoConfig, ModeChange> {

    override val definition = triggerNode<NoConfig, ModeChange>(
        typeId = "trigger.mode_change",
        displayName = "Dark Theme Change",
        description = "Fires when the device's UI / night mode changes",
        category = NodeCategory.DEVICE_STATE,
        icon = NodeIcon.BOLT,
        output = dataOut<ModeChange>("mode", label = "Mode"),
    )

    override fun activate(config: NoConfig, node: WorkflowNode, host: TriggerHost): Flow<NodeOutput<ModeChange>> =
        host.appLifecycleEvents()
            .filter { it.source == TriggerSource.APP }
            .filter { it.payload[KEY_EVENT] == EVENT_MODE }
            .map { bus ->
                NodeOutput(
                    ModeChange(
                        mode = bus.payload[KEY_MODE].orEmpty(),
                        timestamp = DateTime(bus.firedAtEpochMs),
                    ),
                )
            }

    companion object {
        const val KEY_MODE = "mode"
        const val EVENT_MODE = "mode"
    }
}
