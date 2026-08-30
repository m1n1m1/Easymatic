package io.github.m1n1m1.easymatic.engine

import io.github.m1n1m1.easymatic.core.model.PortName
import io.github.m1n1m1.easymatic.domain.model.config.ComparisonType
import io.github.m1n1m1.easymatic.domain.model.schema.Item
import io.github.m1n1m1.easymatic.domain.model.schema.ItemSchema
import io.github.m1n1m1.easymatic.domain.model.schema.asText
import io.github.m1n1m1.easymatic.domain.registry.IF_BOOLEAN_DEFAULT
import io.github.m1n1m1.easymatic.engine.action.CompareConfig

/** The `source` DATA input port a placed `action.if` reads when one is wired. */
internal val SOURCE_PORT = PortName("source")

/** The `value` DATA input port a placed `action.if` compares against. */
internal val COMPARE_PORT = PortName("value")

/**
 * The one comparison in the graph, kept separate from
 * [io.github.m1n1m1.easymatic.engine.action.IfAction] so that what "greater than" means
 * is decided in exactly one place, independent of how the answer is routed.
 *
 * [input] carries the node's own wired inputs. An unreadable source fails closed.
 */
internal suspend fun evaluateCompare(
    config: CompareConfig,
    input: NodeInput,
    context: ExecutionContext,
): Boolean {
    val item = resolveValueSource(config.source, input, context) ?: return false
    // A wired port wins over the form literal on the compare-against side too.
    val literal = input.text(COMPARE_PORT) ?: config.value
    val expected = if (comparesBoolean(config, item)) booleanLiteral(literal) else literal
    return config.operator.matches(inspect(config, item), expected)
}

/** Whether the compared value is a boolean, however that was decided. */
private fun comparesBoolean(config: CompareConfig, item: Item): Boolean {
    val schema = if (config.type == ComparisonType.AUTO) {
        (item.schema as? ItemSchema.Object)?.fields?.get(config.field) ?: item.schema
    } else {
        config.type.schema
    }
    return schema is ItemSchema.Primitive && schema.kClass == Boolean::class
}

/**
 * A boolean literal, with a blank one reading as [IF_BOOLEAN_DEFAULT] — the same
 * default the form shows for one.
 *
 * The compare-against editor for a boolean is a switch, and a node whose switch
 * has never been touched stores nothing at all: the form renders the field's
 * default while the config decodes to `""`. Comparing that raw is the bug this
 * exists to stop — `""` equals neither `"true"` nor `"false"`, so *every*
 * untouched boolean comparison took its false branch no matter what the source
 * said, while the switch on screen claimed to say otherwise.
 */
private fun booleanLiteral(literal: String): String =
    literal.toBooleanStrictOrNull()?.toString() ?: IF_BOOLEAN_DEFAULT

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
