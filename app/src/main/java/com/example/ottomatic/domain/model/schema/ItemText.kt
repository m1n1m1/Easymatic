package com.example.ottomatic.domain.model.schema

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.serializer

/**
 * The text form of this item — the single answer to "how does this look as text?".
 *
 * Used wherever a value has to leave the type system and become characters: the
 * `TEXT` conversion in [com.example.ottomatic.domain.model.config.ValueType], the
 * wired-value resolution in [com.example.ottomatic.domain.registry.NodeSchema],
 * and the comparison in `action.if`. Having exactly one renderer is what stops a
 * notification and a comparison from disagreeing about what the same item says.
 *
 * Primitives render plainly; a struct, list or map renders as compact JSON rather
 * than a Kotlin `toString()`, so it stays readable *and* parseable — a struct
 * converted to text can be fed straight back into `transform.json_read`.
 */
fun Item.asText(): String = when {
    value == null || value == kotlin.Unit -> ""
    schema is ItemSchema.Primitive -> value.toString()
    else -> jsonElementToString(anyToJsonElement(value, schema))
}

/**
 * Best-effort [JsonElement] for a runtime [value] described by [schema] — the
 * loose inverse of [jsonElementToValue], used only by [asText].
 *
 * A struct is encoded through its own serializer (the same route
 * [com.example.ottomatic.engine.action.BreakStructAction] takes); collections
 * recurse; anything whose type cannot be recovered degrades to its `toString()`
 * rather than failing, because rendering text must never throw.
 */
private fun anyToJsonElement(value: Any?, schema: ItemSchema): JsonElement = when (value) {
    null, kotlin.Unit -> JsonNull
    is JsonElement -> value
    is Boolean -> JsonPrimitive(value)
    is Number -> JsonPrimitive(value)
    is String -> JsonPrimitive(value)
    is Map<*, *> -> JsonObject(
        value.entries.associate { (key, entry) -> key.toString() to anyToJsonElement(entry, elementSchema(schema)) },
    )
    is Iterable<*> -> JsonArray(value.map { anyToJsonElement(it, elementSchema(schema)) })
    else -> encodeStruct(value, schema)
}

/** The schema of a collection's elements, or [ItemSchema.Wildcard] when unknown. */
private fun elementSchema(schema: ItemSchema): ItemSchema = when (schema) {
    is ItemSchema.ListSchema -> schema.element
    is ItemSchema.MapSchema -> schema.value
    else -> ItemSchema.Wildcard
}

/** Encodes a `@Serializable` struct through its own serializer, or degrades to `toString()`. */
private fun encodeStruct(value: Any, schema: ItemSchema): JsonElement {
    val kClass = (schema as? ItemSchema.Object)?.kClass ?: return JsonPrimitive(value.toString())
    return runCatching {
        @Suppress("UNCHECKED_CAST")
        Json.encodeToJsonElement(serializer(kClass.java) as KSerializer<Any>, value)
    }.getOrElse { JsonPrimitive(value.toString()) }
}
