package com.example.ottomatic.engine

import com.example.ottomatic.core.model.PortName
import com.example.ottomatic.domain.model.config.ComparisonType
import com.example.ottomatic.domain.model.schema.Item
import com.example.ottomatic.domain.model.schema.ItemSchema
import com.example.ottomatic.domain.model.schema.asText
import com.example.ottomatic.engine.action.CompareConfig

/** The `source` DATA input port a placed `action.if` reads when one is wired. */
internal val SOURCE_PORT = PortName("source")

/** The `value` DATA input port a placed `action.if` compares against. */
internal val COMPARE_PORT = PortName("value")

/**
 * The one comparison in the graph, kept separate from
 * [com.example.ottomatic.engine.action.IfAction] so that what "greater than" means
 * is decided in exactly one place, independent of how the answer is routed.
 *
 * [input] carries the node's own wired inputs. An unreadable source fails closed.
 */
internal suspend fun evaluateCompare(
    config: CompareConfig,
    input: NodeInput,
    context: ExecutionContext,
): Boolean {
    val item = resolveValueSource(config.source, input, context)
    // A wired port wins over the form literal on the compare-against side too.
    val expected = input.text(COMPARE_PORT) ?: config.value
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
    return item.asText()
}
