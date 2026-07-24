package com.example.ottomatic.engine

import com.example.ottomatic.domain.model.WorkflowNode

/**
 * Typed read access over a node's raw [WorkflowNode.config] string map.
 *
 * Each accessor parses the stored string back to the typed value according to
 * the node's [com.example.ottomatic.domain.registry.NodeConfigSchema].
 *
 * Dynamic values that can be wired from upstream data are no longer read here:
 * they are declared as DATA input ports on the node type and read via
 * [ActionInput.string] (which falls back to the static config value when the
 * port is unwired). TypedConfig only reads form-stored literals.
 */
class TypedConfig(
    private val values: Map<String, String>,
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
}
