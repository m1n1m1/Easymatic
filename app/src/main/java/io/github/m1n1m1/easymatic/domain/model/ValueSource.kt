package io.github.m1n1m1.easymatic.domain.model

import io.github.m1n1m1.easymatic.core.model.NodeTypeId

/**
 * Where `action.if` gets the value it inspects.
 *
 * A comparison can read either something wired into its own `source` port or a
 * value node with no edge at all. Rather than two config shapes, both are one
 * *source spec* string in `CompareConfig.source`, parsed here.
 *
 * The spec is persisted, so the prefixes are part of the workflow format:
 *
 *  - `""`            → [Wired], the node's own `source` DATA port
 *  - `val:<typeId>`  → [Value], a value node read on demand
 *
 * This lives in `domain` rather than next to its resolver in `engine` because both
 * the runtime (`resolveValueSource`) and the design-time form derivation
 * ([io.github.m1n1m1.easymatic.domain.registry.effectiveConfigSchema]) must agree on it,
 * and `domain` is the only package both may depend on.
 */
sealed interface ValueSource {

    /** Read the comparison node's own wired `source` port. */
    data object Wired : ValueSource

    /** Read the named value node on demand. */
    data class Value(val typeId: NodeTypeId) : ValueSource

    companion object {
        private const val VALUE_PREFIX = "val:"

        /** The spec selecting the comparison's own wired `source` port. */
        const val WIRED_SPEC = ""

        /** The spec string that selects the value node [typeId]. */
        fun valueSpec(typeId: NodeTypeId): String = "$VALUE_PREFIX${typeId.value}"

        /**
         * Parses a persisted source spec.
         *
         * Anything that is not a `val:` read means [Wired] — blank, and equally a
         * spec left over from a removed form. That is the safe fallback: an unwired
         * `source` port yields no item, so the comparison fails closed rather than
         * inspecting something unintended.
         */
        fun parse(spec: String): ValueSource =
            if (spec.startsWith(VALUE_PREFIX)) {
                Value(NodeTypeId(spec.removePrefix(VALUE_PREFIX)))
            } else {
                Wired
            }
    }
}
