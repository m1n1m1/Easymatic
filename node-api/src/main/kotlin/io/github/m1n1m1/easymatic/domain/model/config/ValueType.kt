package io.github.m1n1m1.easymatic.domain.model.config

import io.github.m1n1m1.easymatic.domain.model.schema.DateTime
import io.github.m1n1m1.easymatic.domain.model.schema.Item
import io.github.m1n1m1.easymatic.domain.model.schema.ItemSchema
import io.github.m1n1m1.easymatic.domain.model.schema.asText
import kotlinx.serialization.Serializable
import kotlin.reflect.KClass

/**
 * A data type a user can ask for, in the five shapes that mean something to
 * someone who is not a programmer.
 *
 * This is the user-facing counterpart to [ItemSchema]: the schema lattice
 * distinguishes Int from Long from Float because Kotlin does, but nobody
 * configuring a macro wants to choose between them. Every numeric port is offered
 * as either [NUMBER] or [WHOLE_NUMBER], and every boolean one as [YES_OR_NO].
 * [DATE_TIME] is the exception that has only one member: a moment is a moment.
 *
 * A [ValueType] therefore names a *family*, and [schema] is only that family's
 * default. When a conversion feeds a port that is specifically `Long` or `Float`,
 * the consuming port pins the exact type — see the `into` parameter of [convert]
 * and the effective-port resolution for `transform.convert`.
 *
 * Used by `transform.convert` and `transform.json_read` as their "what do you want
 * out of this?" setting, and by
 * [io.github.m1n1m1.easymatic.domain.model.schema.conversionTarget] as the answer to
 * "which Convert setting bridges these two ports?".
 */
@Serializable
enum class ValueType(val schema: ItemSchema.Primitive) {
    TEXT(ItemSchema.Primitive(String::class)),
    NUMBER(ItemSchema.Primitive(Double::class)),
    WHOLE_NUMBER(ItemSchema.Primitive(Int::class)),
    YES_OR_NO(ItemSchema.Primitive(Boolean::class)),
    @Label("Date & time")
    DATE_TIME(ItemSchema.Primitive(DateTime::class)),
    ;

    /**
     * Converts [item] to this type, falling back to [fallback] and then to the
     * type's zero value. [into] pins the exact primitive when the consuming port
     * is narrower than the family (a `Long` counter, a `Float` accuracy);
     * it must belong to this family.
     *
     * **Total by construction**: it always produces an item of the requested type
     * and never throws, which is what lets a conversion sit in the middle of a data
     * wire. That permissiveness is only safe because the conversion is *visible* —
     * it is always a `transform.convert` node on the canvas carrying this
     * [fallback] as an editable field, so "43abc became 0" is something the user
     * can see and correct rather than a hidden coercion.
     */
    fun convert(item: Item?, fallback: String = "", into: ItemSchema.Primitive = schema): Item {
        val kClass = into.kClass
        val value = parse(kClass, item?.readFor(kClass)) ?: parse(kClass, fallback) ?: zeroOf(kClass)
        return Item(value = value, schema = into)
    }

    /** True when [schema] is one of the primitives this family covers. */
    fun covers(schema: ItemSchema?): Boolean = of(schema) == this

    companion object {
        /**
         * The family a port of [schema] belongs to, or null when it is not a
         * primitive — a struct, list or map is only ever convertible *to* [TEXT].
         */
        fun of(schema: ItemSchema?): ValueType? = (schema as? ItemSchema.Primitive)?.let {
            when (it.kClass) {
                String::class -> TEXT
                Boolean::class -> YES_OR_NO
                Int::class, Long::class -> WHOLE_NUMBER
                Double::class, Float::class -> NUMBER
                DateTime::class -> DATE_TIME
                else -> null
            }
        }

        /** Parses [text] into [kClass], or null when it does not fit. */
        private fun parse(kClass: KClass<out Any>, text: String?): Any? {
            val raw = text?.takeIf { it.isNotBlank() } ?: return null
            return when (kClass) {
                String::class -> raw
                Boolean::class -> raw.asBoolean() ?: raw.asNumber()?.let { it != 0.0 }
                DateTime::class -> DateTime.parse(raw)
                else -> parseNumber(kClass, raw)
            }
        }

        /**
         * The text of this item as the conversion to [target] should read it.
         *
         * Everything is normally mediated by [asText], but a [DateTime]'s text is
         * ISO-8601 and has no numeric reading — asking for the epoch millis of an
         * event would otherwise land on the fallback. Converting one *to text* still
         * gives the ISO form, which is the readable answer a notification wants.
         */
        private fun Item.readFor(target: KClass<out Any>): String {
            val source = (schema as? ItemSchema.Primitive)?.kClass
            if (source != DateTime::class || target == String::class || target == DateTime::class) return asText()
            return (value as? DateTime)?.epochMs?.toString() ?: asText()
        }

        /**
         * Parses [raw] into a numeric [kClass]. The exact forms come first so a
         * `Long` beyond `Double`'s precision survives; the `Double` reading is the
         * fallback that also handles "3.7" being asked for as a whole number,
         * where truncating is friendlier than refusing.
         */
        private fun parseNumber(kClass: KClass<out Any>, raw: String): Any? {
            val value = raw.asNumber() ?: return null
            return when (kClass) {
                Int::class -> raw.toIntOrNull() ?: value.toInt()
                Long::class -> raw.toLongOrNull() ?: value.toLong()
                Float::class -> raw.toFloatOrNull() ?: value.toFloat()
                else -> value
            }
        }

        /** The value a failed conversion lands on. */
        private fun zeroOf(kClass: KClass<out Any>): Any = when (kClass) {
            Boolean::class -> false
            Int::class -> 0
            Long::class -> 0L
            Double::class -> 0.0
            Float::class -> 0f
            DateTime::class -> DateTime.EPOCH
            else -> ""
        }
    }
}

/** `"true"`/`"yes"`/`"on"` and their negatives, since users type all of them. */
private fun String.asBoolean(): Boolean? = when (trim().lowercase()) {
    "true", "yes", "on" -> true
    "false", "no", "off" -> false
    else -> null
}

/**
 * This text read as a number — including a yes/no answer, so `true` arriving at a
 * numeric port becomes 1 rather than nothing.
 */
private fun String.asNumber(): Double? =
    toDoubleOrNull() ?: asBoolean()?.let { if (it) 1.0 else 0.0 }
