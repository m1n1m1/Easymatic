package com.example.ottomatic.engine.trigger

import com.example.ottomatic.domain.model.WorkflowNode
import com.example.ottomatic.domain.model.schema.Item
import com.example.ottomatic.engine.NodeOutput
import com.example.ottomatic.engine.TriggerNodeDefinition
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Encoded graph output emitted by a trigger when it fires: the typed items the
 * trigger exposes on its DATA output ports, keyed by port name. The executor
 * caches them so downstream data connections can read them. The trigger's
 * EXECUTION `out` port is implicitly pulsed — every trigger has a single exec
 * output named `"out"`.
 */
typealias TriggerOutput = NodeOutput<Map<String, Item>>

/** Non-generic activation bridge used by the heterogeneous trigger registry. */
interface ExecutableTrigger {

    val definition: TriggerNodeDefinition<*>

    val typeId: String get() = definition.typeId

    /**
     * Returns the encoded stream of events for this particular placed node.
     * The [host] provides access to Android-backed sources.
     * Cancelling the flow collection tears down any resources the trigger armed.
     */
    fun activateEncoded(node: WorkflowNode, host: TriggerHost): Flow<TriggerOutput>
}

/**
 * A unit of executable behaviour behind a node whose
 * [com.example.ottomatic.domain.model.NodeTypeDefinition] has
 * [com.example.ottomatic.domain.model.NodeKind.TRIGGER].
 *
 * Whether the source is event-driven (broadcast, notification listener) or
 * poll-based (WorkManager), it is reduced to a single [Flow] of typed events.
 * The engine subscribes the same way regardless of the underlying mechanism.
 *
 * The trigger's [definition] is the node's single declaration (metadata,
 * ports, config fields and output encoder); it lives in the trigger's own
 * file. Implementations are registered in
 * [com.example.ottomatic.domain.registry.TriggerRegistry].
 */
interface Trigger<O : Any> : ExecutableTrigger {

    override val definition: TriggerNodeDefinition<O>

    /**
     * Returns the typed stream of events for this particular placed node; the
     * [definition]'s encoder maps each event onto the declared DATA ports.
     */
    fun activate(node: WorkflowNode, host: TriggerHost): Flow<NodeOutput<O>>

    override fun activateEncoded(node: WorkflowNode, host: TriggerHost): Flow<TriggerOutput> =
        activate(node, host).map { output ->
            NodeOutput(
                value = definition.encodeData(output.value),
                route = output.route,
                halt = output.halt,
            )
        }
}
