package io.github.m1n1m1.easymatic.data.ai

import io.github.m1n1m1.easymatic.core.service.AiParam
import io.github.m1n1m1.easymatic.core.service.AiParamSchema
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * [AiParam] rendered as JSON Schema.
 *
 * **Written once for all three providers, unlike everything else in this package**,
 * and that is not an inconsistency — it is the same test applied and answered the
 * other way. The *envelope* around a tool differs per provider (`functionDeclarations`
 * / `tools` / `tools[].function`) and so lives in each protocol; the *argument schema*
 * genuinely does not, because all three took it from JSON Schema unchanged. Rendering
 * it three times would be three chances to disagree about how a list of strings is
 * spelled.
 *
 * The subset is small on purpose: Gemini's function declarations accept only a subset
 * of JSON Schema, so staying inside the intersection is what keeps one renderer
 * viable. That intersection is exactly [AiParamSchema]'s five members.
 */
internal fun toolParameterSchema(parameters: List<AiParam>): JsonObject = buildJsonObject {
    put(TYPE, OBJECT)
    putJsonObject(PROPERTIES) {
        parameters.forEach { put(it.name, describe(it)) }
    }
    // Omitted entirely when empty rather than sent as `[]`: several servers treat an
    // empty required array as a schema error, and "nothing is required" is what an
    // absent key already means.
    val required = parameters.filter { it.required }.map { it.name }
    if (required.isNotEmpty()) {
        putJsonArray(REQUIRED) { required.forEach { add(it) } }
    }
}

/** One parameter, with its description folded in. */
private fun describe(parameter: AiParam): JsonObject = buildJsonObject {
    render(parameter.schema)
    if (parameter.description.isNotBlank()) put(DESCRIPTION, parameter.description)
}

/**
 * The type half of a parameter, without its description.
 *
 * Split out because a list's *element* has a shape but no description of its own —
 * `{type: "array", items: {type: "string"}}` — so the two halves cannot be one
 * function.
 */
private fun kotlinx.serialization.json.JsonObjectBuilder.render(schema: AiParamSchema) {
    when (schema) {
        is AiParamSchema.Text -> {
            put(TYPE, STRING)
            // An enum is what turns a field the model has to guess into one it can
            // only get right — the same argument `@Picker` makes in the form.
            if (schema.options.isNotEmpty()) {
                putJsonArray(ENUM) { schema.options.forEach { add(it) } }
            }
        }
        AiParamSchema.Integer -> put(TYPE, INTEGER)
        AiParamSchema.Decimal -> put(TYPE, NUMBER)
        AiParamSchema.Flag -> put(TYPE, BOOLEAN)
        is AiParamSchema.Items -> {
            put(TYPE, ARRAY)
            putJsonObject(ITEMS) { render(schema.element) }
        }
    }
}

private const val TYPE = "type"
private const val OBJECT = "object"
private const val STRING = "string"
private const val INTEGER = "integer"
private const val NUMBER = "number"
private const val BOOLEAN = "boolean"
private const val ARRAY = "array"
private const val ITEMS = "items"
private const val ENUM = "enum"
private const val PROPERTIES = "properties"
private const val REQUIRED = "required"
private const val DESCRIPTION = "description"
