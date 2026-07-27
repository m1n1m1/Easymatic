package com.example.ottomatic.domain.model.schema

import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
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
 * [flat] is a precomputed `Map<String, String>` view of [value]'s top-level
 * fields (for struct values), used by `action.if` to read a selected
 * field of a connected struct item by name. Non-struct values carry an empty
 * flat view.
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
 * [Item.of] to precompute the struct-field view consumed by `action.if`.
 */
@PublishedApi
internal inline fun <reified T : Any> flattenItem(value: T): Map<String, String> {
    val element = Json.encodeToJsonElement(serializer<T>(), value)
    if (element !is JsonObject) return emptyMap()
    return buildMap {
        element.forEach { (key, child) -> put(key, jsonElementToString(child)) }
    }
}

/**
 * Renders a [JsonElement] as the flat string form used by config defaults and
 * by [Item.flat]. [JsonNull] must be matched before [JsonPrimitive] — it is a
 * subclass whose `content` is the literal text `"null"`.
 */
@PublishedApi
internal fun jsonElementToString(element: JsonElement): String = when (element) {
    is JsonNull -> ""
    is JsonPrimitive -> element.content
    else -> element.toString()
}

/**
 * Converts a [JsonElement] to its typed Kotlin value according to [schema].
 *
 * Used by the adaptive struct node ([com.example.ottomatic.engine.action.BreakStructAction])
 * to decode a struct's JSON form into the per-field typed values its DATA
 * output ports carry. Primitives become String/Long/Int/Boolean/Double/Float;
 * lists recurse element-wise; maps of string-keyed string-valued entries
 * become `Map<String,String>`; nested objects/unions/wildcards stay as
 * [JsonElement] (their kClass is not always resolvable, and round-tripping
 * through JSON is the documented contract for these schemas).
 */
@Suppress("CyclomaticComplexMethod", "ComplexMethod")
internal fun jsonElementToValue(element: JsonElement, schema: ItemSchema): Any? = when (schema) {
    is ItemSchema.Primitive -> when (schema.kClass) {
        String::class -> (element as? JsonPrimitive)?.contentOrNull ?: ""
        Int::class -> (element as? JsonPrimitive)?.intOrNull ?: 0
        Long::class -> (element as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: 0L
        Boolean::class -> (element as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull() ?: false
        Double::class -> (element as? JsonPrimitive)?.doubleOrNull ?: 0.0
        Float::class -> (element as? JsonPrimitive)?.floatOrNull ?: 0f
        // Reached both from our own struct encoding (ISO text) and from a foreign
        // JSON body read through `transform.json_read` (epoch millis or seconds);
        // the parse ladder covers all of them.
        DateTime::class -> DateTime.parse((element as? JsonPrimitive)?.contentOrNull) ?: DateTime.EPOCH
        else -> (element as? JsonPrimitive)?.contentOrNull ?: ""
    }
    is ItemSchema.MapSchema -> decodeStringMap(element)
    is ItemSchema.ListSchema -> {
        (element as? JsonArray)?.map { jsonElementToValue(it, schema.element) } ?: emptyList<Any>()
    }
    is ItemSchema.Object -> element
    is ItemSchema.Wildcard -> element
    is ItemSchema.Unit -> kotlin.Unit
    is ItemSchema.Union -> element
}

/** Decodes a JSON object of string-keyed string-valued entries to a `Map<String,String>`. */
private fun decodeStringMap(element: JsonElement): Map<String, String> {
    val obj = element as? JsonObject ?: return emptyMap()
    return buildMap {
        obj.forEach { (k, v) -> put(k, (v as? JsonPrimitive)?.contentOrNull ?: "") }
    }
}

/**
 * Builds the struct-field `flat` view for a single field [value] described by
 * [schema]. Only `Map<String,String>` values carry a flat view (their entries
 * become addressable keys); all other field types resolve via [Item.value]'s
 * `toString()`.
 */
internal fun flatViewFor(value: Any?, schema: ItemSchema): Map<String, String> =
    if (schema is ItemSchema.MapSchema && value is Map<*, *>) {
        @Suppress("UNCHECKED_CAST")
        value as Map<String, String>
    } else {
        emptyMap()
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
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
internal fun buildSchemaNotNull(
    descriptor: SerialDescriptor,
    kClass: KClass<out Any>?,
): ItemSchema {
    // `PrimitiveKind` is closed, so a DateTime cannot announce itself through its
    // kind — it reports STRING. Its serial name is the only hook, and it has to be
    // checked first or every timestamp collapses back into a plain text port.
    if (descriptor.serialName == DateTime.SERIAL_NAME) return ItemSchema.Primitive(DateTime::class)
    return buildDeclaredSchema(descriptor, kClass)
}

/** The declared-kind mapping, once [buildSchemaNotNull] has ruled out a named primitive. */
@Suppress("ComplexMethod")
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
private fun buildDeclaredSchema(
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
