package com.example.ottomatic.engine.trigger

import com.example.ottomatic.domain.model.WorkflowNode
import com.example.ottomatic.domain.model.schema.Item
import kotlinx.coroutines.flow.Flow

/**
 * The event a trigger produces when it fires.
 *
 * [dataOut] carries the typed data items the trigger exposes on its DATA
 * output ports (e.g. `mapOf("sms" to Item.of(SmsMessage(...)))` for
 * `trigger.sms`). The executor caches them so downstream EXPR interpolation
 * and typed data connections can read them. The trigger's EXECUTION `out`
 * port is implicitly pulsed — every trigger has a single exec output named
 * `"out"`.
 */
data class TriggerEvent(
    val triggerNodeId: String,
    val dataOut: Map<String, Item> = emptyMap(),
)

/**
 * A unit of executable behaviour behind a node whose
 * [com.example.ottomatic.domain.model.NodeTypeDefinition] has
 * [com.example.ottomatic.domain.model.NodeKind.TRIGGER].
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
