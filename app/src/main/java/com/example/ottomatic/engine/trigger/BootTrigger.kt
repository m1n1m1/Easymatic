package com.example.ottomatic.engine.trigger

import com.example.ottomatic.core.trigger.TriggerSource
import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.WorkflowNode
import com.example.ottomatic.engine.NodeOutput
import com.example.ottomatic.engine.triggerNode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map

/**
 * Trigger for `trigger.boot`. Fires once after the device finishes booting.
 * The [com.example.ottomatic.data.trigger.BootReceiver] pushes a sentinel
 * event onto the bus; this trigger maps it to the concrete node id.
 */
class BootTrigger : Trigger<Unit> {

    override val definition = triggerNode<Unit>(
        typeId = "trigger.boot",
        displayName = "Device Boot",
        description = "Starts once after the device finishes booting",
        category = NodeCategory.POWER_BATTERY,
        iconKey = "boot",
        encodeData = { emptyMap() },
    )

    override fun activate(node: WorkflowNode, host: TriggerHost): Flow<NodeOutput<Unit>> =
        host.busEvents()
            .filter { it.source == TriggerSource.BOOT }
            .map { NodeOutput(Unit) }
}
