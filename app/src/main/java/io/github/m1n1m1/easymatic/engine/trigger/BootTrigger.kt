package io.github.m1n1m1.easymatic.engine.trigger

import io.github.m1n1m1.easymatic.core.trigger.TriggerSource
import io.github.m1n1m1.easymatic.domain.model.NodeCategory
import io.github.m1n1m1.easymatic.domain.model.NodeIcon
import io.github.m1n1m1.easymatic.domain.model.WorkflowNode
import io.github.m1n1m1.easymatic.domain.model.config.NoConfig
import io.github.m1n1m1.easymatic.engine.NodeOutput
import io.github.m1n1m1.easymatic.engine.pulseTriggerNode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map

/**
 * Trigger for `trigger.boot`. Fires once after the device finishes booting.
 * The [io.github.m1n1m1.easymatic.data.trigger.BootReceiver] pushes a sentinel
 * event onto the bus; this trigger maps it to the concrete node id.
 */
class BootTrigger : Trigger<NoConfig, Unit> {

    override val definition = pulseTriggerNode<NoConfig>(
        typeId = "trigger.boot",
        displayName = "Device Boot",
        description = "Starts once after the device finishes booting",
        category = NodeCategory.POWER_BATTERY,
        icon = NodeIcon.BOOT,
    )

    // `busEventsFor` rather than `busEvents`, and it is the whole reason this
    // trigger works at all: BOOT_COMPLETED arrives at the one moment nothing can
    // be collecting, so the receiver parks the event and only a node-addressed
    // collector drains it. Collecting the raw flow meant racing the engine's own
    // start and usually losing.
    override fun activate(config: NoConfig, node: WorkflowNode, host: TriggerHost): Flow<NodeOutput<Unit>> =
        host.busEventsFor(node.id)
            .filter { it.source == TriggerSource.BOOT }
            .map { NodeOutput(Unit) }
}
