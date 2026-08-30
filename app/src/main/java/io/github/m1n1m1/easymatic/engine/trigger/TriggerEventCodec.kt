package io.github.m1n1m1.easymatic.engine.trigger

import io.github.m1n1m1.easymatic.core.model.PortName
import io.github.m1n1m1.easymatic.domain.model.schema.Item
import io.github.m1n1m1.easymatic.engine.NodeOutput
import io.github.m1n1m1.easymatic.nodeapi.wire.PluginJson
import io.github.m1n1m1.easymatic.nodeapi.wire.TriggerEventWire
import io.github.m1n1m1.easymatic.nodeapi.wire.toItem
import io.github.m1n1m1.easymatic.nodeapi.wire.toWire

/**
 * The one reading of a [TriggerEventWire] as a [TriggerOutput], and the one writing
 * of the reverse.
 *
 * Two things now start a run with data that did not come from the graph — a plugin's
 * trigger callback and a call through the process API — and both carry it in the
 * same wire. Extracted from `PluginTriggerBridge`, where it was private, rather than
 * copied: two decoders of one format is how they eventually disagree about a
 * `DateTime`, and the symptom would be a value that is right on one path and epoch
 * zero on the other.
 *
 * The wire is used for the API path even though nothing there is a plugin, because
 * what is needed is exactly what it already does: carry a map of port name to
 * *typed* item across a process boundary as text. A second format would be the same
 * format with a different name.
 */
fun TriggerEventWire.toTriggerOutput(): NodeOutput<Map<PortName, Item>> =
    NodeOutput(data.entries.associate { (name, wire) -> PortName(name) to wire.toItem() })

/**
 * [json] read as a trigger event, or null when it is not one.
 *
 * Null rather than an exception because both callers are holding untrusted text from
 * another process; a malformed payload is a call to refuse, not a crash.
 */
fun triggerOutputFrom(json: String): NodeOutput<Map<PortName, Item>>? =
    runCatching { PluginJson.decodeFromString(TriggerEventWire.serializer(), json) }
        .getOrNull()
        ?.toTriggerOutput()

/**
 * These items as a wire event.
 *
 * An item whose schema has no wire spelling is **dropped** rather than failing the
 * whole event: the port it was bound for simply reads as unwired, which is a state
 * every consumer already handles, where losing the other five values is not.
 */
fun Map<PortName, Item>.toTriggerEventWire(): TriggerEventWire =
    TriggerEventWire(data = mapNotNull { (name, item) -> item.toWire()?.let { name.value to it } }.toMap())

/** [TriggerEventWire] as the string that travels in an Intent extra. */
fun TriggerEventWire.encodeToString(): String =
    PluginJson.encodeToString(TriggerEventWire.serializer(), this)
