package com.example.ottomatic.core.model

import kotlinx.serialization.Serializable

/**
 * Identifier types for the workflow graph.
 *
 * Every one of these used to be a bare `String`, and they were routinely passed
 * through the same signatures: the executor keyed its data cache on a
 * `Pair<String, String>` of node id and port name, the registries looked nodes up
 * by "id" meaning *type* id, and the config form wrote `(String, String)` pairs of
 * key and value. Nothing but discipline stopped one being used where another was
 * expected.
 *
 * These are `@JvmInline value class`es, so they cost nothing at runtime and
 * serialize as plain strings — the persisted JSON is unchanged.
 *
 * They live in `core/` because every layer needs them, including the
 * `TriggerBus` that connects Android callbacks to the engine.
 */

/** Identifies a *kind* of node, e.g. `action.notify`. Registry lookup key. */
@JvmInline
@Serializable
value class NodeTypeId(val value: String) {
    override fun toString(): String = value
}

/** Identifies a node *placed* on a workflow canvas. */
@JvmInline
@Serializable
value class NodeId(val value: String) {
    override fun toString(): String = value

    companion object {
        /**
         * Sentinel used by fan-out sources in `data/`: a broadcast (SMS,
         * charging, …) is not addressed to one placed node, so every trigger of
         * that kind receives it.
         */
        val BROADCAST = NodeId("*")
    }
}

/** Identifies a port on a node, e.g. `out`, `state`, `url`. */
@JvmInline
@Serializable
value class PortName(val value: String) {
    override fun toString(): String = value
}

/** Identifies a field in a node's configuration form. */
@JvmInline
@Serializable
value class ConfigKey(val value: String) {
    override fun toString(): String = value
}
