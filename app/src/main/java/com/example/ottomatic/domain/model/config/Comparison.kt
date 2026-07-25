package com.example.ottomatic.domain.model.config

import com.example.ottomatic.domain.model.schema.ItemSchema
import kotlinx.serialization.Serializable
import kotlin.reflect.KClass

/**
 * How `condition.compare` should interpret the value it inspects.
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
    ;

    /** The pinned schema, or null for [AUTO]. */
    val schema: ItemSchema.Primitive? get() = primitive?.let { ItemSchema.Primitive(it) }
}

/**
 * A comparison performed by `condition.compare`.
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

    /** Evaluates this comparison of [actual] against [expected]. */
    fun matches(actual: String, expected: String): Boolean = when (this) {
        EQUALS -> actual == expected
        NOT_EQUALS -> actual != expected
        CONTAINS -> actual.contains(expected)
        MATCHES_REGEX -> runCatching { actual.matches(Regex(expected)) }.getOrDefault(false)
        GREATER_THAN, LESS_THAN, GREATER_THAN_OR_EQUAL, LESS_THAN_OR_EQUAL -> compareNumbers(actual, expected)
    }

    private fun compareNumbers(actual: String, expected: String): Boolean {
        val left = actual.toDoubleOrNull()
        val right = expected.toDoubleOrNull()
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
            schema.kClass in NUMERIC -> ORDERING
            schema.kClass == String::class -> TEXT
            else -> EQUALITY
        }

        private val NUMERIC = setOf(Int::class, Long::class, Double::class, Float::class)
    }
}
