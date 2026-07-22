package com.example.ottomatic.engine.trigger

import com.example.ottomatic.core.trigger.TriggerSource
import com.example.ottomatic.domain.model.WorkflowNode
import com.example.ottomatic.domain.model.items.ModeChange
import com.example.ottomatic.domain.model.schema.Item
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
class ModeChangeTrigger : Trigger {

    override val typeId: String = TYPE_ID

    override fun activate(node: WorkflowNode, host: TriggerHost): Flow<TriggerEvent> =
        host.appLifecycleEvents()
            .filter { it.source == TriggerSource.APP }
            .filter { it.payload[KEY_EVENT] == EVENT_MODE }
            .map { bus ->
                val mode = bus.payload[KEY_MODE].orEmpty()
                TriggerEvent(
                    triggerNodeId = node.id,
                    dataOut = mapOf(
                        "mode" to Item.of(
                            ModeChange(
                                mode = mode,
                                timestamp = bus.firedAtEpochMs,
                            ),
                        ),
                    ),
                )
            }

    companion object {
        const val TYPE_ID = "trigger.mode_change"

        const val KEY_EVENT = "event"
        const val KEY_MODE = "mode"
        const val EVENT_MODE = "mode"
    }
}
