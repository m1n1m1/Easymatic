package io.github.m1n1m1.easymatic.engine.trigger

import io.github.m1n1m1.easymatic.core.model.NodeTypeId
import io.github.m1n1m1.easymatic.core.model.PortName
import io.github.m1n1m1.easymatic.domain.model.WorkflowNode
import io.github.m1n1m1.easymatic.domain.registry.decode
import io.github.m1n1m1.easymatic.domain.model.schema.Item
import io.github.m1n1m1.easymatic.engine.NodeOutput
import io.github.m1n1m1.easymatic.engine.TriggerNodeDefinition
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Encoded graph output emitted by a trigger when it fires: the typed items the
 * trigger exposes on its DATA output ports, keyed by port name. The executor
 * caches them so downstream data connections can read them. The trigger's
 * EXECUTION `out` port is implicitly pulsed.
 */
typealias TriggerOutput = NodeOutput<Map<PortName, Item>>

/** Non-generic activation bridge used by the heterogeneous trigger registry. */
interface ExecutableTrigger {

    val definition: TriggerNodeDefinition<*, *>

    val typeId: NodeTypeId get() = definition.typeId

    /**
     * Returns the encoded stream of events for this particular placed node.
     * The [host] provides access to Android-backed sources.
     * Cancelling the flow collection tears down any resources the trigger armed.
     */
    fun activateEncoded(node: WorkflowNode, host: TriggerHost): Flow<TriggerOutput>
}

/**
 * A unit of executable behaviour behind a node whose
 * [io.github.m1n1m1.easymatic.domain.model.NodeTypeDefinition] has
 * [io.github.m1n1m1.easymatic.domain.model.NodeKind.TRIGGER].
 *
 * Whether the source is event-driven (broadcast, notification listener) or
 * poll-based (WorkManager), it is reduced to a single [Flow] of typed events.
 * The engine subscribes the same way regardless of the underlying mechanism.
 *
 * A trigger receives its configuration as a decoded [C] — it never reads
 * [WorkflowNode.config] itself, so a trigger cannot depend on a config value it
 * has not declared. [node] is still passed for its identity (arming
 * node-scoped Android resources).
 */
interface Trigger<C : Any, O : Any> : ExecutableTrigger {

    override val definition: TriggerNodeDefinition<C, O>

    /** Returns the typed stream of events for this particular placed node. */
    fun activate(config: C, node: WorkflowNode, host: TriggerHost): Flow<NodeOutput<O>>

    override fun activateEncoded(node: WorkflowNode, host: TriggerHost): Flow<TriggerOutput> =
        activate(definition.schema.decode(node), node, host).map { output ->
            NodeOutput(
                value = definition.encode(output.value),
                route = output.route,
                halt = output.halt,
            )
        }
}
