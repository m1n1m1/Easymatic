package com.example.ottomatic.engine.transform

import com.example.ottomatic.core.service.LogLevel
import com.example.ottomatic.domain.model.Direction
import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.NodeIcon
import com.example.ottomatic.domain.model.Port
import com.example.ottomatic.domain.model.PortKind
import com.example.ottomatic.domain.model.config.Label
import com.example.ottomatic.domain.model.config.Multiline
import com.example.ottomatic.domain.model.config.ValueType
import com.example.ottomatic.domain.model.config.Wired
import com.example.ottomatic.domain.model.schema.Item
import com.example.ottomatic.domain.model.schema.ItemSchema
import com.example.ottomatic.domain.registry.JSON_READ_TYPE_ID
import com.example.ottomatic.domain.registry.TRANSFORM_OUT
import com.example.ottomatic.engine.ExecutionContext
import com.example.ottomatic.engine.NodeInput
import com.example.ottomatic.engine.RawTransform
import com.example.ottomatic.engine.adaptiveTransformNode
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Config for `transform.json_read`.
 *
 * [json] is `@Wired`, so the usual wiring is `action.http` → `action.break` →
 * `body` → here. [path] addresses one value inside it.
 */
@Serializable
data class JsonReadConfig(
    @Label("JSON text") @Multiline @Wired val json: String = "",
    @Label("Path (e.g. main.temp)") val path: String = "",
    @Label("Get as") val type: ValueType = ValueType.TEXT,
    @Label("If missing") val fallback: String = "",
)

/**
 * `transform.json_read` — reads one value out of a piece of JSON text.
 *
 * The path is dot-separated, and a numeric segment indexes into an array:
 * `main.temp`, `items.0.price`. `items[0].price` means the same thing — the
 * bracket form is normalised away, because both spellings are what people type.
 *
 * A missing path, a wrong type or malformed JSON all land on [JsonReadConfig.fallback]
 * rather than failing the run: this is a pure read on the pull side, and there is
 * no execution wire here to route a failure down. Anyone who needs to *act* on
 * "the field was missing" compares the result with `action.if`.
 */
class JsonReadTransform : RawTransform<JsonReadConfig> {

    override val definition = adaptiveTransformNode<JsonReadConfig>(
        typeId = JSON_READ_TYPE_ID.value,
        displayName = "Read from JSON",
        description = "Reads one value out of JSON text, such as an HTTP response body",
        category = NodeCategory.TRANSFORM_DATA,
        icon = NodeIcon.JSON,
        output = Port(
            name = TRANSFORM_OUT,
            kind = PortKind.DATA,
            direction = Direction.OUT,
            schema = ItemSchema.Wildcard,
            label = "Value",
        ),
    )

    override suspend fun transformItem(
        config: JsonReadConfig,
        input: NodeInput,
        context: ExecutionContext,
    ): Item {
        val found = readPath(config.json, config.path)
        if (found == null) {
            context.log("Read from JSON: path '${config.path}' not found", LogLevel.WARN)
        }
        // Route the found element back through the shared text form so a JSON
        // number, a JSON string and a nested object all convert identically.
        return config.type.convert(found?.let { Item(value = it, schema = ItemSchema.Wildcard) }, config.fallback)
    }
}

/** The element at [path] inside [json], or null when either is malformed or absent. */
internal fun readPath(json: String, path: String): JsonElement? {
    val root = runCatching { Json.parseToJsonElement(json.trim()) }.getOrNull() ?: return null
    return pathSegments(path).fold(root as JsonElement?) { current, segment -> current?.child(segment) }
}

/** `items[0].price` and `items.0.price` are the same path; blank segments are dropped. */
private fun pathSegments(path: String): List<String> =
    path.replace('[', '.').replace("]", "").split('.').filter { it.isNotBlank() }

/** The named field, or the indexed element when [segment] is a number. */
private fun JsonElement.child(segment: String): JsonElement? = when (this) {
    is JsonObject -> this[segment]
    is JsonArray -> segment.toIntOrNull()?.let { getOrNull(it) }
    else -> null
}
