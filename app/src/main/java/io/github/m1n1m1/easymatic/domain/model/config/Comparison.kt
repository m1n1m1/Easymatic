package io.github.m1n1m1.easymatic.domain.model.config

import io.github.m1n1m1.easymatic.domain.model.schema.DateTime
import io.github.m1n1m1.easymatic.domain.model.schema.ItemSchema
import kotlinx.serialization.Serializable
import kotlin.reflect.KClass

/**
 * How `action.if` should interpret the value it inspects.
 *
 * [AUTO] infers the comparison from the schema of whatever is wired into the
 * node's `source` port (and exposes a field picker when that is a struct); every
 * other entry pins the comparison — and the `source` port's schema — to one
 * primitive, so the node can be configured before anything is connected.
 */
@Serializable
enum class ComparisonType(private val primitive: KClass<out Any>?) {
    AUTO(null),
    INT(Int::class),
    LONG(Long::class),
    DOUBLE(Double::class),
    FLOAT(Float::class),
    BOOLEAN(Boolean::class),
    STRING(String::class),
    DATE_TIME(DateTime::class),
    ;

    /** The pinned schema, or null for [AUTO]. */
    val schema: ItemSchema.Primitive? get() = primitive?.let { ItemSchema.Primitive(it) }
}

/**
 * A comparison performed by `action.if`.
 *
 * Each operator carries its own evaluation, and [forSchema] narrows the set
 * offered for a given value type — so the operator list in the config form and
 * the behaviour at runtime cannot drift apart, as they could when both were
 * driven by matching string literals.
 */
@Serializable
enum class ComparisonOperator {
    EQUALS,
    NOT_EQUALS,
    GREATER_THAN,
    LESS_THAN,
    GREATER_THAN_OR_EQUAL,
    LESS_THAN_OR_EQUAL,
    CONTAINS,
    MATCHES_REGEX,
    ;

    /**
     * Evaluates this comparison of [actual] against [expected].
     *
     * [EQUALS] and [NOT_EQUALS] compare the text as written, so two identical
     * moments spelled with different UTC offsets do not match — the ordering
     * operators are the ones that understand a date.
     */
    fun matches(actual: String, expected: String): Boolean = when (this) {
        EQUALS -> actual == expected
        NOT_EQUALS -> actual != expected
        CONTAINS -> actual.contains(expected)
        MATCHES_REGEX -> runCatching { actual.matches(Regex(expected)) }.getOrDefault(false)
        GREATER_THAN, LESS_THAN, GREATER_THAN_OR_EQUAL, LESS_THAN_OR_EQUAL -> compareNumbers(actual, expected)
    }

    private fun compareNumbers(actual: String, expected: String): Boolean {
        val left = actual.asOrdered()
        val right = expected.asOrdered()
        return when {
            left == null || right == null -> false
            this == GREATER_THAN -> left > right
            this == LESS_THAN -> left < right
            this == GREATER_THAN_OR_EQUAL -> left >= right
            this == LESS_THAN_OR_EQUAL -> left <= right
            else -> false
        }
    }

    companion object {
        private val EQUALITY = listOf(EQUALS, NOT_EQUALS)
        private val ORDERING = EQUALITY +
            listOf(GREATER_THAN, LESS_THAN, GREATER_THAN_OR_EQUAL, LESS_THAN_OR_EQUAL)
        private val TEXT = EQUALITY + listOf(CONTAINS, MATCHES_REGEX)

        /** The operators that make sense for a value described by [schema]. */
        fun forSchema(schema: ItemSchema?): List<ComparisonOperator> = when {
            schema !is ItemSchema.Primitive -> EQUALITY
            schema.kClass in ORDERED -> ORDERING
            schema.kClass == String::class -> TEXT
            else -> EQUALITY
        }

        /** The primitives with a meaningful "before/after", dates included. */
        private val ORDERED = setOf(Int::class, Long::class, Double::class, Float::class, DateTime::class)
    }
}

/**
 * This text as an orderable number: a plain number, or a moment read as its epoch
 * milliseconds.
 *
 * The date rung is what keeps [ComparisonOperator.GREATER_THAN] meaningful once a
 * timestamp renders as ISO-8601 — text order breaks across UTC offsets. It also
 * lets the compare-against side be written the way a person would ("18:00",
 * "2026-07-27") and still line up with a full timestamp. Epoch millis are exact in
 * a `Double` for any date this app will see.
 */
private fun String.asOrdered(): Double? = toDoubleOrNull() ?: DateTime.parse(this)?.epochMs?.toDouble()
