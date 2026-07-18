package com.example.ottomatic.engine.trigger

import com.example.ottomatic.domain.model.WorkflowNode
import kotlinx.coroutines.flow.Flow

/**
 * The event a trigger produces. Carried into the workflow as the initial
 * [com.example.ottomatic.engine.WorkflowPayload].
 */
data class TriggerEvent(
    val triggerNodeId: String,
    val payload: Map<String, String> = emptyMap(),
)

/**
 * A unit of executable behaviour behind a node whose [NodeTypeDefinition]
 * has [NodeKind.TRIGGER].
 *
 * Whether the source is event-driven (broadcast, notification listener) or
 * poll-based (WorkManager), it is reduced to a single [Flow] of events.
 * The engine subscribes the same way regardless of the underlying mechanism.
 *
 * Implementations live in `engine/trigger/` and are registered in
 * [com.example.ottomatic.domain.registry.TriggerRegistry].
 */
interface Trigger {

    val typeId: String

    /**
     * Returns the stream of events for this particular placed node.
     * The [host] provides access to Android-backed sources.
     * Cancelling the flow collection tears down any resources the trigger armed.
     */
    fun activate(node: WorkflowNode, host: TriggerHost): Flow<TriggerEvent>
}
