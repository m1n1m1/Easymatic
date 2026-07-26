package com.example.ottomatic.engine

import com.example.ottomatic.core.model.PortName
import com.example.ottomatic.domain.model.config.ComparisonType
import com.example.ottomatic.domain.model.schema.Item
import com.example.ottomatic.domain.model.schema.ItemSchema
import com.example.ottomatic.engine.action.CompareConfig

/** The `source` DATA input port a placed `action.if` reads when one is wired. */
internal val SOURCE_PORT = PortName("source")

/** The `value` DATA input port a placed `action.if` compares against. */
internal val COMPARE_PORT = PortName("value")

/**
 * The one comparison in the graph, shared by both of its placements.
 *
 * [com.example.ottomatic.engine.action.IfAction] calls this and turns the result
 * into a `true`/`false` exec route; [conditionsPass] calls it and uses the Boolean
 * directly to gate a node. Neither re-implements the comparison, so an attached
 * gate and a placed if-node can never disagree about what "greater than" means.
 *
 * [input] carries the host's ports — the placed node's own wired inputs, or the
 * gate host's collected inputs. It is null only when there is no host to read
 * from, in which case a `val:` source still resolves and everything else fails
 * closed.
 */
internal suspend fun evaluateCompare(
    config: CompareConfig,
    input: NodeInput?,
    context: ExecutionContext,
): Boolean {
    val item = resolveValueSource(config.source, input, context)
    // A wired port wins over the form literal on the compare-against side too.
    val expected = input?.text(COMPARE_PORT) ?: config.value
    val actual = item?.let { inspect(config, it) } ?: return false
    return config.operator.matches(actual, expected)
}

/**
 * The comparable text of [item]: a selected struct field in auto mode, else the
 * whole value.
 */
private fun inspect(config: CompareConfig, item: Item): String {
    if (config.type == ComparisonType.AUTO && item.schema is ItemSchema.Object) {
        return item.flat[config.field].orEmpty()
    }
    return item.value?.toString().orEmpty()
}
