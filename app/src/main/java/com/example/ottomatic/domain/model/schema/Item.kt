package com.example.ottomatic.domain.model.schema

import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.serializer
import kotlin.reflect.KClass

/**
 * A single typed data value flowing on a
 * [com.example.ottomatic.domain.model.Port] of kind
 * [com.example.ottomatic.domain.model.PortKind.DATA].
 *
 * [value] is the actual Kotlin object (a data class instance, a primitive, a
 * list, ...); [schema] describes its shape and is used by the graph validator
 * and by the executor to type-check connections at design time and runtime.
 * [flat] is a precomputed `Map<String, String>` view of [value]'s fields, used
 * by the executor to build the EXPR interpolation context so HTTP/notify/etc.
 * can reference upstream data via `{{field}}` placeholders.
 *
 * `Item` is a *runtime* object only — it is never persisted. The persisted
 * workflow graph refers to nodes and ports; the values flowing through them
 * exist only while a workflow is executing.
 */
class Item(
    val value: Any?,
    val schema: ItemSchema,
    val flat: Map<String, String> = emptyMap(),
) {

    override fun toString(): String = "Item(value=$value, schema=$schema)"

    override fun equals(other: Any?): Boolean =
        this === other || (other is Item && value == other.value && schema == other.schema)

    override fun hashCode(): Int = (value?.hashCode() ?: 0) * 31 + schema.hashCode()

    companion object {
        /** An item carrying no data (used on EXECUTION ports). */
        val EMPTY: Item = Item(value = kotlin.Unit, schema = ItemSchema.Unit)

        /** Wraps a plain Kotlin value, deriving its [ItemSchema] and [flat] view. */
        inline fun <reified T : Any> of(value: T): Item =
            Item(value = value, schema = schemaOf<T>(), flat = flattenItem(value))
    }
}

/** Builds the [ItemSchema] for a reified `@Serializable` type [T]. */
inline fun <reified T : Any> schemaOf(): ItemSchema = buildSchema(serializer<T>().descriptor, T::class)

/** Casts an [Item]'s [Item.value] to [T], throwing if the schema disagrees. */
inline fun <reified T : Any> Item.asTyped(): T {
    val expected = schemaOf<T>()
    require(expected.isAssignableFrom(schema)) {
        "Schema mismatch: expected $expected but got $schema"
    }
    @Suppress("UNCHECKED_CAST")
    return value as T
}

/**
 * Flattens a `@Serializable` [value] into `Map<String, String>` by
 * serializing it to JSON and unwrapping each top-level field. Used by
 * [Item.of] to precompute the EXPR-interpolation view.
 */
@PublishedApi
internal inline fun <reified T : Any> flattenItem(value: T): Map<String, String> {
    val element = Json.encodeToJsonElement(serializer<T>(), value)
    if (element !is JsonObject) return emptyMap()
    return buildMap {
        element.forEach { (key, child) -> put(key, jsonElementToString(child)) }
    }
}

@PublishedApi
internal fun jsonElementToString(element: JsonElement): String = when (element) {
    is JsonPrimitive -> element.content
    is JsonNull -> ""
    else -> element.toString()
}

/** Builds an [ItemSchema] from a kotlinx-serialization [descriptor][SerialDescriptor]. */
@PublishedApi
internal fun buildSchema(
    descriptor: SerialDescriptor,
    kClass: KClass<out Any>?,
): ItemSchema {
    if (descriptor.isNullable) {
        return ItemSchema.Union(listOf(buildSchemaNotNull(descriptor, kClass), ItemSchema.Unit))
    }
    return buildSchemaNotNull(descriptor, kClass)
}

@PublishedApi
@Suppress("ComplexMethod")
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
internal fun buildSchemaNotNull(
    descriptor: SerialDescriptor,
    kClass: KClass<out Any>?,
): ItemSchema = when (descriptor.kind) {
    PrimitiveKind.STRING -> ItemSchema.Primitive(String::class)
    PrimitiveKind.INT -> ItemSchema.Primitive(Int::class)
    PrimitiveKind.LONG -> ItemSchema.Primitive(Long::class)
    PrimitiveKind.DOUBLE -> ItemSchema.Primitive(Double::class)
    PrimitiveKind.FLOAT -> ItemSchema.Primitive(Float::class)
    PrimitiveKind.BOOLEAN -> ItemSchema.Primitive(Boolean::class)
    PrimitiveKind.BYTE -> ItemSchema.Primitive(Int::class)
    PrimitiveKind.SHORT -> ItemSchema.Primitive(Int::class)
    PrimitiveKind.CHAR -> ItemSchema.Primitive(String::class)
    StructureKind.LIST -> ItemSchema.ListSchema(buildSchema(descriptor.getElementDescriptor(0), null))
    SerialKind.ENUM -> ItemSchema.Primitive(String::class)
    StructureKind.MAP -> ItemSchema.MapSchema(
        key = buildSchema(descriptor.getElementDescriptor(0), null),
        value = buildSchema(descriptor.getElementDescriptor(1), null),
    )
    StructureKind.CLASS,
    StructureKind.OBJECT,
    -> {
        val fields = linkedMapOf<String, ItemSchema>()
        for (i in 0 until descriptor.elementsCount) {
            fields[descriptor.getElementName(i)] = buildSchema(descriptor.getElementDescriptor(i), null)
        }
        ItemSchema.Object(fields, kClass)
    }
    SerialKind.CONTEXTUAL,
    is PolymorphicKind,
    -> ItemSchema.Wildcard
}
