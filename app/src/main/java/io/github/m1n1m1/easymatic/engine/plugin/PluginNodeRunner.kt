package io.github.m1n1m1.easymatic.engine.plugin

import io.github.m1n1m1.easymatic.core.model.PortName
import io.github.m1n1m1.easymatic.core.service.LogLevel
import io.github.m1n1m1.easymatic.domain.model.WorkflowNode
import io.github.m1n1m1.easymatic.domain.model.schema.Item
import io.github.m1n1m1.easymatic.domain.registry.PluginNodeEntry
import io.github.m1n1m1.easymatic.domain.registry.PluginNodes
import io.github.m1n1m1.easymatic.domain.registry.intentChoiceUris
import io.github.m1n1m1.easymatic.engine.EncodedNodeOutput
import io.github.m1n1m1.easymatic.engine.ExecutionContext
import io.github.m1n1m1.easymatic.nodeapi.plugin.routesFor
import io.github.m1n1m1.easymatic.nodeapi.wire.ActionResultWire
import io.github.m1n1m1.easymatic.nodeapi.wire.ItemWire
import io.github.m1n1m1.easymatic.nodeapi.wire.LogLevelWire
import io.github.m1n1m1.easymatic.nodeapi.wire.LogLineWire
import io.github.m1n1m1.easymatic.nodeapi.wire.NodeCallWire
import io.github.m1n1m1.easymatic.nodeapi.wire.PluginJson
import io.github.m1n1m1.easymatic.nodeapi.wire.ValueResultWire
import io.github.m1n1m1.easymatic.nodeapi.wire.toItem
import io.github.m1n1m1.easymatic.nodeapi.wire.toWire
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Running a node that lives in another app.
 *
 * A plugin node is deliberately **not** an `ExecutableAction`, `ValueNode` or
 * `ExecutableTransform`. Every one of those requires an `ActionNodeDefinition` /
 * `ValueNodeDefinition` / `TransformNodeDefinition`, which is built from a
 * `NodeSchema<C>` over a reified Kotlin config class — and a plugin's config class
 * does not exist in this process and never will. Giving a bridge a fabricated
 * definition would mean an object whose `nodeType` lies about its own ports, waiting
 * for the first caller who reads it. So the executor asks here instead, at the three
 * points where it looks a node up, and the four registries keep describing exactly
 * what they always described: the app's own compiled nodes.
 *
 * ## The timeouts are here, not in the transport
 *
 * `BinderPluginChannel` could enforce them and it deliberately does not, because a
 * timeout is the single most interesting thing this layer does and a fake channel in a
 * test has to be able to provoke it. Putting `withTimeoutOrNull` on this side means
 * every timeout is exercised by a JVM test that simply delays.
 *
 * ## Every failure is a result
 *
 * Nothing here throws. The executor's own wrapper would catch it and log
 * "Action … failed", which names the typeId but says nothing about *which plugin* or
 * *why* — and for a value it would be worse still, because the pull side's contract is
 * that a read answers null. So each failure is reported here, in words, naming the
 * plugin, and then degrades exactly as the equivalent first-party failure does.
 */
@Suppress("TooManyFunctions") // Three node kinds, one transport, and the encode/decode either side.
object PluginNodeRunner {

    /** An action is on the execution wire, where latency is visible but tolerable. */
    private const val ACTION_TIMEOUT_MS = 30_000L

    /**
     * A read is not.
     *
     * The pull side's contract is that a value is cheap and cannot fail, so it gets a
     * bound an order of magnitude tighter than an action's. A read that overruns
     * answers null, the consumer falls back to its form value, and a comparison fails
     * closed — the degradation that already existed, reached by a new route.
     */
    private const val READ_TIMEOUT_MS = 2_000L

    /** True when this node belongs to a plugin at all. */
    fun isPluginNode(node: WorkflowNode): Boolean = PluginNodes.byId(node.typeId) != null

    /**
     * Runs a plugin action, or null when this typeId is not one.
     *
     * A null answer from the plugin — an unreachable process, a timeout, a malformed
     * reply — still pulses, with no data, because halting the branch would be a stronger
     * claim than the host can make: the node may well have done its work and failed only
     * on the way back.
     *
     * **Which port it pulses is the node's first declared route**, not a literal `out`.
     * That was a hard-coded `"out"` until protocol 2, which a `BRANCH` node does not
     * have at all — so an unreachable branching plugin pulsed a port nothing could be
     * wired to, and execution stopped dead with only a log line to say so. The same
     * ordering rule that makes `routeOf` safe covers this: the first route a declaration
     * names is the one that means *carried on*.
     */
    @Suppress("ReturnCount") // Not-ours, could-not-run, and ran — three genuinely different outcomes.
    suspend fun runAction(
        node: WorkflowNode,
        data: Map<PortName, Item>,
        context: ExecutionContext,
    ): EncodedNodeOutput? {
        val entry = PluginNodes.byId(node.typeId) ?: return null
        val reply = call(entry, context, ACTION_TIMEOUT_MS, node, data) { channel, request ->
            channel.runAction(entry.definition.typeId.value, request)
        }
        val result = reply?.let { decode(it, ActionResultWire.serializer()) }
        if (result == null) {
            context.log("${entry.pluginName} did not answer; carrying on", LogLevel.ERROR)
            return EncodedNodeOutput(listOf(PortName(fallbackRoute(entry))), emptyMap(), halt = false)
        }
        result.log.replayInto(context)
        return EncodedNodeOutput(
            execOut = listOf(PortName(routeOf(entry, result, context))),
            dataOut = result.data.toItems(),
            halt = result.halt,
        )
    }

    /** Reads a plugin value, or null when this typeId is not one or the read failed. */
    suspend fun readValue(node: WorkflowNode, context: ExecutionContext): Item? {
        val entry = PluginNodes.byId(node.typeId) ?: return null
        return readOne(entry, node, emptyMap(), context) { channel, request ->
            channel.readValue(entry.definition.typeId.value, request)
        }
    }

    /** Runs a plugin transform, or null when this typeId is not one or it failed. */
    suspend fun runTransform(
        node: WorkflowNode,
        data: Map<PortName, Item>,
        context: ExecutionContext,
    ): Item? {
        val entry = PluginNodes.byId(node.typeId) ?: return null
        return readOne(entry, node, data, context) { channel, request ->
            channel.runTransform(entry.definition.typeId.value, request)
        }
    }

    private suspend fun readOne(
        entry: PluginNodeEntry,
        node: WorkflowNode,
        data: Map<PortName, Item>,
        context: ExecutionContext,
        send: suspend (io.github.m1n1m1.easymatic.nodeapi.plugin.PluginChannel, String) -> String?,
    ): Item? {
        val reply = call(entry, context, READ_TIMEOUT_MS, node, data, send)
        val result = reply?.let { decode(it, ValueResultWire.serializer()) } ?: return null
        result.log.replayInto(context)
        return result.item?.toItem()
    }

    /**
     * Encodes the call, sends it under [timeoutMs], and reports whatever went wrong.
     *
     * [node] and [data] have no defaults on purpose. They did, and the omission that
     * cost was silent and total: an action forgot to pass them, so every plugin action
     * ran with its form completely unset and simply looked broken. Required parameters
     * make the same mistake a compile error.
     *
     * ## The lend, and why it is bounded to exactly this
     *
     * A config value chosen through an `@IntentChoice` chooser may be a `content://` URI
     * this app holds a grant on and the plugin's process holds nothing on. The string goes
     * over like every other config value; the *grant* is lent beside it, by package, for the
     * length of this call, and taken back in the `finally` — so a plugin that stores the URI
     * away finds it dead when it comes back to it. Nothing about the wire changes: see
     * `PluginChannel.lend`.
     *
     * It wraps the timeout rather than sitting inside it, because the plugin may still be
     * reading when the host gives up waiting, and revoking a grant out from under a read in
     * progress would turn a slow plugin into a failing one.
     */
    @Suppress("LongParameterList")
    private suspend fun call(
        entry: PluginNodeEntry,
        context: ExecutionContext,
        timeoutMs: Long,
        node: WorkflowNode,
        data: Map<PortName, Item>,
        send: suspend (io.github.m1n1m1.easymatic.nodeapi.plugin.PluginChannel, String) -> String?,
    ): String? {
        val request = PluginJson.encodeToString(
            NodeCallWire.serializer(),
            NodeCallWire(
                config = node.config.mapKeys { (key, _) -> key.value },
                // An item whose type has no wire form is dropped rather than sent as
                // text: the consumer then falls back to its form value, which is the
                // same degradation an unwired port already has.
                data = data.mapNotNull { (name, item) -> item.toWire()?.let { name.value to it } }.toMap(),
            ),
        )
        val lent = intentChoiceUris(entry.configSchema, node.config)
        if (lent.isNotEmpty()) entry.channel.lend(lent)
        val answer = try {
            withTimeoutOrNull(timeoutMs) {
                runCatching { send(entry.channel, request) }.getOrElse { cause ->
                    if (cause is kotlinx.coroutines.CancellationException) throw cause
                    context.log("${entry.pluginName} failed: ${cause.message}", LogLevel.ERROR)
                    null
                }
            }
        } finally {
            if (lent.isNotEmpty()) entry.channel.withdraw(lent)
        }
        if (answer == null) {
            context.log(
                "${entry.pluginName} did not answer within ${timeoutMs / MILLIS_PER_SECOND}s",
                LogLevel.WARN,
            )
        }
        return answer
    }

    /**
     * The port the action asked to pulse, refused if the node never declared it.
     *
     * The host `require`s the same of its own nodes — a route not among a definition's
     * `execOutputs` throws — and a plugin is not a reason to relax it: pulsing a port
     * that is not on the card would send execution down an edge the user cannot see.
     */
    private fun routeOf(entry: PluginNodeEntry, result: ActionResultWire, context: ExecutionContext): String {
        val declared = routesFor(entry.declaration)
        if (result.route in declared) return result.route
        context.log(
            "${entry.pluginName} asked to continue from '${result.route}', which this node " +
                "does not have; using '${fallbackRoute(entry)}' instead",
            LogLevel.WARN,
        )
        return fallbackRoute(entry)
    }

    /**
     * The route the host takes when it cannot honour the one it was given.
     *
     * The **first** a declaration names, because that is where a plugin is required to
     * put the outcome meaning *carried on* — and both callers need it to mean that. An
     * unroutable reply is at least evidence the node ran; an unreachable plugin is not
     * even that, and the host still may not claim the work did not happen, because the
     * call may have failed on the way back. `"out"` is the last resort for a declaration
     * with no execution outputs at all, which the validator already rejects.
     */
    private fun fallbackRoute(entry: PluginNodeEntry): String =
        routesFor(entry.declaration).firstOrNull() ?: "out"

    private fun <T> decode(json: String, serializer: kotlinx.serialization.KSerializer<T>): T? =
        runCatching { PluginJson.decodeFromString(serializer, json) }.getOrNull()

    private fun Map<String, ItemWire>.toItems(): Map<PortName, Item> =
        entries.associate { (name, wire) -> PortName(name) to wire.toItem() }

    /** Writes the plugin's own lines into the run log, at the levels it chose. */
    private fun List<LogLineWire>.replayInto(context: ExecutionContext) {
        for (line in this) {
            val level = when (line.level) {
                LogLevelWire.DEBUG -> LogLevel.DEBUG
                LogLevelWire.INFO -> LogLevel.INFO
                LogLevelWire.WARN -> LogLevel.WARN
                LogLevelWire.ERROR -> LogLevel.ERROR
            }
            context.log(line.message, level)
        }
    }

    private const val MILLIS_PER_SECOND = 1000
}
