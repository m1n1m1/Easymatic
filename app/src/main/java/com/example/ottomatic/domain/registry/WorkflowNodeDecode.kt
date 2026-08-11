package com.example.ottomatic.domain.registry

import com.example.ottomatic.core.model.PortName
import com.example.ottomatic.domain.model.WorkflowNode
import com.example.ottomatic.domain.model.schema.Item

/**
 * Builds the typed config for a placed [node] from its own config map.
 *
 * An extension here rather than a second `decode` overload on [NodeSchema], and the
 * reason is the one [com.example.ottomatic.engine.ExecutionContext] already states
 * about itself: a node body never sees its own [WorkflowNode]. [NodeSchema] lives in
 * `:node-api`, which a third-party plugin compiles against, and [WorkflowNode] is a
 * *persistence* type — the graph's storage format, with a schema version and a
 * discard-rather-than-migrate rule behind it. Keeping the overload out of that module
 * means the plugin-visible API cannot name it, which turns a documented invariant into
 * a compile error.
 *
 * Every call site is unchanged: `schema.decode(node)` still resolves, now through an
 * import of this file.
 */
fun <T : Any> NodeSchema<T>.decode(node: WorkflowNode, data: Map<PortName, Item> = emptyMap()): T =
    decode(node.config, data)
