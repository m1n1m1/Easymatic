package com.example.ottomatic.engine

import com.example.ottomatic.domain.model.WorkflowNode

/**
 * Typed read access over a node's raw [WorkflowNode.config] string map.
 *
 * Each accessor parses the stored string back to the typed value according to
 * the node's [com.example.ottomatic.domain.registry.NodeConfigSchema]. The
 * [expr] accessor additionally interpolates `{{field}}` placeholders against
 * the runtime [dataContext] (a flat string view of all data items produced
 * upstream in the current execution chain).
 */
class TypedConfig(
    private val values: Map<String, String>,
    private val dataContext: Map<String, String>,
) {

    /** Raw stored string for [key], or null if unset. */
    fun raw(key: String): String? = values[key]

    /** String value for [key], or [default] when blank/missing. */
    fun str(key: String, default: String = ""): String =
        values[key]?.takeIf { it.isNotBlank() } ?: default

    /** Integer value for [key], or [default]. */
    fun int(key: String, default: Int = 0): Int = values[key]?.toIntOrNull() ?: default

    /** Boolean value for [key] ("true"/"false"), or [default]. */
    fun bool(key: String, default: Boolean = false): Boolean =
        values[key]?.toBooleanStrictOrNull() ?: default

    /** Double value for [key], or [default]. */
    fun double(key: String, default: Double = 0.0): Double = values[key]?.toDoubleOrNull() ?: default

    /** Interpolates `{{field}}` placeholders in the stored template against [dataContext]. */
    fun expr(key: String, default: String = ""): String =
        interpolate(str(key, default), dataContext)
}

/**
 * Replaces `{{key}}` placeholders in [template] with matching [context] values.
 * Used by EXPR-typed config fields (http url/body, notify text, ...).
 */
fun interpolate(template: String, context: Map<String, String>): String {
    if (template.isEmpty()) return template
    var result = template
    context.forEach { (key, value) ->
        result = result.replace("{{$key}}", value)
    }
    return result
}
