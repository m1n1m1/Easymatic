package io.github.m1n1m1.easymatic.plugin

import android.content.Context
import io.github.m1n1m1.easymatic.core.model.ConfigKey
import io.github.m1n1m1.easymatic.core.model.PortName
import io.github.m1n1m1.easymatic.domain.model.schema.Item
import io.github.m1n1m1.easymatic.nodeapi.plugin.PluginLimits
import io.github.m1n1m1.easymatic.nodeapi.wire.ActionResultWire
import io.github.m1n1m1.easymatic.nodeapi.wire.ItemWire
import io.github.m1n1m1.easymatic.nodeapi.wire.LogLevelWire
import io.github.m1n1m1.easymatic.nodeapi.wire.LogLineWire
import io.github.m1n1m1.easymatic.nodeapi.wire.NodeCallWire
import io.github.m1n1m1.easymatic.nodeapi.wire.TriggerEventWire
import io.github.m1n1m1.easymatic.nodeapi.wire.ValueResultWire
import io.github.m1n1m1.easymatic.nodeapi.wire.toItem
import io.github.m1n1m1.easymatic.nodeapi.wire.toWire

/*
 * All of a plugin's marshalling, once, generically.
 *
 * A plugin author writes none of this and never sees a `NodeCallWire`: they write
 * `execute(config, context)` taking their own `@Serializable` config class and
 * answering their own `@Serializable` payload. Everything between that and the binder
 * — decoding a config map, resolving a wired item ahead of its form value, encoding a
 * typed result onto a port, collecting log lines — is here, written once, for every
 * plugin that will ever exist.
 *
 * What cannot be generic is choosing *which* node a typeId names. That is the one
 * `when` the KSP processor generates, and the only generated code on the plugin side.
 *
 * These are public rather than internal because that generated `when` is compiled
 * into the *plugin's* module, not this one. They are plumbing an author is not
 * expected to call.
 */

/** A [PluginContext] that buffers its log lines to travel back with the call's result. */
class RecordingPluginContext(override val android: Context) : PluginContext {

    private val lines = mutableListOf<LogLineWire>()
    private var dropped = 0

    override fun log(message: String, level: LogLevelWire) {
        if (lines.size >= PluginLimits.MAX_LOG_LINES) {
            dropped++
            return
        }
        lines += LogLineWire(level, message.take(PluginLimits.MAX_LOG_CHARS))
    }

    /**
     * The buffered lines, with a note when any were dropped.
     *
     * Announced rather than silent, on `MAX_ITERATIONS`' stance: a log that quietly
     * stops is worse than one that says it stopped, because the reader concludes
     * nothing else happened.
     */
    fun drain(): List<LogLineWire> = when (dropped) {
        0 -> lines.toList()
        else -> lines + LogLineWire(
            LogLevelWire.WARN,
            "$dropped more log ${if (dropped == 1) "line" else "lines"} were dropped " +
                "(a node may write ${PluginLimits.MAX_LOG_LINES} per run)",
        )
    }
}

/** Runs this action against a decoded call and encodes what it answered. */
suspend fun <C : Any, O : Any> PluginAction<C, O>.dispatch(call: NodeCallWire, android: Context): ActionResultWire {
    val context = RecordingPluginContext(android)
    val output = execute(definition.decodeConfig(call), context)
    val port = definition.output
    // A null value emits *no entry* rather than an encoded blank: an action that failed
    // has nothing to describe, and a struct of zeroes on the port reads downstream
    // exactly like a success. See `PluginOutput.value`.
    val encoded = output.value
        ?.let { value -> port?.encode(value)?.toWire()?.let { mapOf(port.name.value to it) } }
        .orEmpty()
    return ActionResultWire(
        data = encoded,
        route = output.route,
        halt = output.halt,
        log = context.drain(),
    )
}

/**
 * Reads this value against a decoded call.
 *
 * A null answer is legitimate and is where every failure lands: the host's consumer
 * falls back to its form value and a comparison fails closed.
 */
suspend fun <C : Any, O : Any> PluginValue<C, O>.dispatch(call: NodeCallWire, android: Context): ValueResultWire {
    val context = RecordingPluginContext(android)
    val answer = read(definition.decodeConfig(call), context)
    return ValueResultWire(item = answer?.let { definition.encodeOne(it) }, log = context.drain())
}

/** Runs this transform against a decoded call. */
suspend fun <C : Any, O : Any> PluginTransform<C, O>.dispatch(
    call: NodeCallWire,
    android: Context,
): ValueResultWire {
    val context = RecordingPluginContext(android)
    val answer = transform(definition.decodeConfig(call), context)
    return ValueResultWire(item = answer?.let { definition.encodeOne(it) }, log = context.drain())
}

/**
 * Decodes a node's config from a call.
 *
 * Wired items win over form values, exactly as they do for a first-party node — the
 * resolution order is [io.github.m1n1m1.easymatic.domain.registry.NodeSchema]'s, shared
 * rather than reimplemented, which is what keeps a plugin's `@Wired` property behaving
 * the way the socket beside it on the card promises.
 */
fun <C : Any, O : Any> PluginNodeDefinition<C, O>.decodeConfig(call: NodeCallWire): C = schema.decode(
    config = call.config.mapKeys { (key, _) -> ConfigKey(key) },
    data = call.data.mapKeys { (name, _) -> PortName(name) }.mapValues { (_, wire) -> wire.toItem() },
)

/** Encodes one payload onto this node's single data output port. */
fun <C : Any, O : Any> PluginNodeDefinition<C, O>.encodeOne(value: O): ItemWire? =
    output?.encode(value)?.toWire()

/** Encodes a trigger's payload as the event the host will publish. */
fun <C : Any, O : Any> PluginNodeDefinition<C, O>.encodeEvent(value: O): TriggerEventWire {
    val port = output ?: return TriggerEventWire()
    val item: Item = port.encode(value)
    return item.toWire()
        ?.let { TriggerEventWire(data = mapOf(port.name.value to it)) }
        ?: TriggerEventWire()
}
