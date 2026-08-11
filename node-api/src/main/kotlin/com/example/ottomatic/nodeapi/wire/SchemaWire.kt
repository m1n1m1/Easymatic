package com.example.ottomatic.nodeapi.wire

import com.example.ottomatic.domain.model.schema.DateTime
import com.example.ottomatic.domain.model.schema.ItemSchema
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The primitives a port can carry, as a closed set — and the closedness is the
 * whole point rather than a convenience.
 *
 * [ItemSchema.Primitive] holds a `KClass`, and [ItemSchema.isAssignableFrom] is
 * *invariant* on it: an `Int` output is never silently accepted by a `Text` input.
 * A class *name* on the wire would therefore let a plugin declare
 * `Primitive(com.acme.Money::class)` — a port that nothing in the graph could
 * connect to, that `conversionTarget` could not convert, and that would render as
 * a colour the palette has no legend for. It would look like a typed port and be
 * a dead end.
 *
 * So the wire offers exactly the seven the graph actually has, per
 * [ItemSchema.Primitive]'s own KDoc. `MAX_SCHEMA_DEPTH` bounds the recursion; this
 * bounds the leaves.
 */
@Serializable
enum class PrimitiveWire(val kClass: kotlin.reflect.KClass<out Any>) {
    TEXT(String::class),
    INT(Int::class),
    LONG(Long::class),
    DOUBLE(Double::class),
    FLOAT(Float::class),
    BOOL(Boolean::class),
    DATE_TIME(DateTime::class),
    ;

    companion object {
        private val byClass = entries.associateBy { it.kClass }

        /** The wire form of [kClass], or null when it is not one of the seven. */
        fun of(kClass: kotlin.reflect.KClass<out Any>): PrimitiveWire? = byClass[kClass]
    }
}

/**
 * The serializable mirror of [ItemSchema].
 *
 * [ItemSchema] cannot cross a process itself: it carries `KClass` tokens, which
 * name classes the other side does not have. This carries the *shape* and nothing
 * else, which is all the type system ever consults — `isAssignableFrom` reads
 * fields, elements and primitive identity, never a class's members.
 *
 * The one deliberate loss is [ItemSchema.Object.kClass]. A plugin's struct has no
 * host class, so it comes back null and the struct's value travels as JSON. See
 * [ItemWire] for what follows from that.
 */
@Serializable
sealed interface SchemaWire {

    @Serializable
    @SerialName("any")
    data object Wildcard : SchemaWire

    @Serializable
    @SerialName("unit")
    data object Unit : SchemaWire

    @Serializable
    @SerialName("prim")
    data class Primitive(val kind: PrimitiveWire) : SchemaWire

    @Serializable
    @SerialName("obj")
    data class Object(val fields: Map<String, SchemaWire>) : SchemaWire

    @Serializable
    @SerialName("list")
    data class ListOf(val element: SchemaWire) : SchemaWire

    @Serializable
    @SerialName("map")
    data class MapOf(val key: SchemaWire, val value: SchemaWire) : SchemaWire

    @Serializable
    @SerialName("union")
    data class Union(val alternatives: List<SchemaWire>) : SchemaWire
}

/**
 * This schema as the graph's own [ItemSchema].
 *
 * [ItemSchema.Object.kClass] is always null: there is no host class behind a
 * plugin's struct, and inventing one is exactly what must not happen.
 */
fun SchemaWire.toItemSchema(): ItemSchema = when (this) {
    SchemaWire.Wildcard -> ItemSchema.Wildcard
    SchemaWire.Unit -> ItemSchema.Unit
    is SchemaWire.Primitive -> ItemSchema.Primitive(kind.kClass)
    is SchemaWire.Object -> ItemSchema.Object(fields.mapValues { (_, value) -> value.toItemSchema() })
    is SchemaWire.ListOf -> ItemSchema.ListSchema(element.toItemSchema())
    is SchemaWire.MapOf -> ItemSchema.MapSchema(key.toItemSchema(), value.toItemSchema())
    is SchemaWire.Union -> ItemSchema.Union(alternatives.map { it.toItemSchema() })
}

/**
 * This schema's wire form, or null when it names a primitive the wire has no
 * member for.
 *
 * Null rather than a degradation to text: a port silently retyped from
 * `com.acme.Money` to `Text` would connect to things it should refuse, which is a
 * worse failure than the port not being offered at all. The caller reports it.
 */
fun ItemSchema.toWire(): SchemaWire? = when (this) {
    ItemSchema.Wildcard -> SchemaWire.Wildcard
    ItemSchema.Unit -> SchemaWire.Unit
    is ItemSchema.Primitive -> PrimitiveWire.of(kClass)?.let { SchemaWire.Primitive(it) }
    is ItemSchema.Object -> fields.mapValuesOrNull { it.toWire() }?.let { SchemaWire.Object(it) }
    is ItemSchema.ListSchema -> element.toWire()?.let { SchemaWire.ListOf(it) }
    is ItemSchema.MapSchema -> key.toWire()?.let { k -> value.toWire()?.let { SchemaWire.MapOf(k, it) } }
    is ItemSchema.Union -> alternatives.mapOrNull { it.toWire() }?.let { SchemaWire.Union(it) }
}

/**
 * This schema's wire form, or an error naming [what] could not be represented.
 *
 * For the declaring side, where there is no sensible degradation: a port typed on
 * something outside the graph's seven primitives is one nothing could connect to and
 * no conversion could reach, so failing while the node is being declared — which
 * happens the first time its class is constructed — beats shipping a dead socket.
 */
fun ItemSchema.toWireOrThrow(what: String): SchemaWire = toWire() ?: error(
    "$what is typed on something the graph has no port colour for. A port must be text, " +
        "a number, a yes/no, a date & time, or a list, map or @Serializable struct of those.",
)

/** How deeply this schema nests, counting itself as 1. Bounded by `MAX_SCHEMA_DEPTH`. */
fun SchemaWire.depth(): Int = when (this) {
    SchemaWire.Wildcard, SchemaWire.Unit -> 1
    is SchemaWire.Primitive -> 1
    is SchemaWire.Object -> 1 + (fields.values.maxOfOrNull { it.depth() } ?: 0)
    is SchemaWire.ListOf -> 1 + element.depth()
    is SchemaWire.MapOf -> 1 + maxOf(key.depth(), value.depth())
    is SchemaWire.Union -> 1 + (alternatives.maxOfOrNull { it.depth() } ?: 0)
}

private inline fun <T, R : Any> List<T>.mapOrNull(transform: (T) -> R?): List<R>? {
    val out = ArrayList<R>(size)
    for (item in this) out.add(transform(item) ?: return null)
    return out
}

private inline fun <K, V, R : Any> Map<K, V>.mapValuesOrNull(transform: (V) -> R?): Map<K, R>? {
    val out = LinkedHashMap<K, R>(size)
    for ((key, value) in this) out[key] = transform(value) ?: return null
    return out
}
