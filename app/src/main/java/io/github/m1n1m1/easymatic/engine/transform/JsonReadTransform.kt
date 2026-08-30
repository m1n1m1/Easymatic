package io.github.m1n1m1.easymatic.engine.transform

import io.github.m1n1m1.easymatic.core.service.LogLevel
import io.github.m1n1m1.easymatic.domain.model.Direction
import io.github.m1n1m1.easymatic.domain.model.NodeCategory
import io.github.m1n1m1.easymatic.domain.model.NodeIcon
import io.github.m1n1m1.easymatic.domain.model.Port
import io.github.m1n1m1.easymatic.domain.model.PortKind
import io.github.m1n1m1.easymatic.domain.model.config.Hint
import io.github.m1n1m1.easymatic.domain.model.config.Label
import io.github.m1n1m1.easymatic.domain.model.config.Multiline
import io.github.m1n1m1.easymatic.domain.model.config.ValueType
import io.github.m1n1m1.easymatic.domain.model.config.Wired
import io.github.m1n1m1.easymatic.domain.model.schema.Item
import io.github.m1n1m1.easymatic.domain.model.schema.ItemSchema
import io.github.m1n1m1.easymatic.domain.registry.JSON_READ_TYPE_ID
import io.github.m1n1m1.easymatic.domain.registry.TRANSFORM_OUT
import io.github.m1n1m1.easymatic.engine.ExecutionContext
import io.github.m1n1m1.easymatic.engine.NodeInput
import io.github.m1n1m1.easymatic.engine.RawTransform
import io.github.m1n1m1.easymatic.engine.adaptiveTransformNode
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
 *
 * [list] says the path lands on an *array*, and turns the output port into a list
 * of [type]. Leaving [path] blank then reads the whole document — which is how a
 * list stored in a variable comes back out, since a variable holds the array's JSON
 * text and `value.variable` hands it over as text.
 */
@Serializable
data class JsonReadConfig(
    @Label("JSON text") @Multiline @Wired val json: String = "",
    @Label("Path")
    @Hint("e.g. main.temp — blank for the whole document")
    val path: String = "",
    @Label("Get as") val type: ValueType = ValueType.TEXT,
    @Label("This is a list") val list: Boolean = false,
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
        if (config.list) return readList(config, found, context)
        // Route the found element back through the shared text form so a JSON
        // number, a JSON string and a nested object all convert identically.
        return config.type.convert(found?.let { Item(value = it, schema = ItemSchema.Wildcard) }, config.fallback)
    }

    /**
     * The found array as a list of [JsonReadConfig.type], one element at a time
     * through the same total conversion the scalar branch uses — so a stray `null`
     * in an array of numbers lands on the fallback rather than losing the whole
     * list.
     *
     * A path that matched something which is *not* an array reads as empty rather
     * than as a one-element list: quietly wrapping a single object would make a
     * mistyped path look like it worked, and the warning is what the user needs.
     */
    private fun readList(config: JsonReadConfig, found: JsonElement?, context: ExecutionContext): Item {
        val array = found as? JsonArray
        if (found != null && array == null) {
            context.log("Read from JSON: '${config.path}' is not a list", LogLevel.WARN)
        }
        val converted = array.orEmpty().map { element ->
            config.type.convert(Item(value = element, schema = ItemSchema.Wildcard), config.fallback)
        }
        return Item(
            value = converted.map { it.value },
            schema = ItemSchema.ListSchema(config.type.schema),
        )
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
