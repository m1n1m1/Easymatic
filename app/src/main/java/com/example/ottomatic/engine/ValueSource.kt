package com.example.ottomatic.engine

import com.example.ottomatic.core.model.NodeTypeId
import com.example.ottomatic.domain.model.ValueSource
import com.example.ottomatic.domain.model.schema.Item
import com.example.ottomatic.domain.registry.ValueRegistry

/**
 * Resolves a [ValueSource] spec to the item a comparison should inspect, or null
 * when it cannot be read.
 *
 * This is the runtime half of [ValueSource]; the spec format itself lives in
 * `domain` because the design-time form derivation needs it too.
 *
 * [input] carries the comparison's own wired inputs. A [ValueSource.Value] needs no
 * ports at all, which is what lets `action.if` read a device value with no edge
 * drawn to it.
 */
internal suspend fun resolveValueSource(
    spec: String,
    input: NodeInput,
    context: ExecutionContext,
): Item? = when (val source = ValueSource.parse(spec)) {
    ValueSource.Wired -> input.item(SOURCE_PORT)
    is ValueSource.Value -> readValueNode(source.typeId, context)
}

/**
 * Reads a value node by id, logging the outcome.
 *
 * An edgeless `val:` read has no node-by-node line in the run log of its own, so
 * without this a comparison that fails on an unreadable subsystem would be silent.
 */
private suspend fun readValueNode(typeId: NodeTypeId, context: ExecutionContext): Item? {
    val value = ValueRegistry.byId(typeId)
    if (value == null) {
        context.log("Unknown value ${typeId.value}: treating as unavailable")
        return null
    }
    val item = runCatching { value.readRaw(emptyMap(), context) }.getOrElse { cause ->
        context.log("Read ${typeId.value} failed: ${cause.message}")
        null
    }
    if (item == null) {
        context.log("Read ${typeId.value}: unavailable")
    } else {
        context.log("Read ${typeId.value} = ${item.value}")
    }
    return item
}
