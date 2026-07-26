package com.example.ottomatic.domain.model

import com.example.ottomatic.core.model.NodeTypeId
import com.example.ottomatic.core.model.PortName

/**
 * Where a comparison gets the value it inspects.
 *
 * `action.if` and an attached gate are the same comparison in two placements, but
 * they reach their input differently: a placed node can have an edge wired into
 * its own `source` port, whereas an attached gate has no ports at all. Rather than
 * two config shapes, both store one *source spec* string in
 * `CompareConfig.source`, parsed here.
 *
 * The spec is persisted, so the prefixes are part of the workflow format:
 *
 *  - `""`             → [Wired], the node's own `source` DATA port (placed only)
 *  - `in:<portName>`  → [HostPort], a DATA input port of the host node (attached only)
 *  - `val:<typeId>`   → [Value], a value node read on demand (either placement)
 *
 * This lives in `domain` rather than next to its resolver in `engine` because both
 * the runtime (`resolveValueSource`) and the design-time form derivation
 * ([com.example.ottomatic.domain.registry.effectiveConfigSchema]) must agree on it,
 * and `domain` is the only package both may depend on.
 */
sealed interface ValueSource {

    /** Read the comparison node's own wired `source` port. */
    data object Wired : ValueSource

    /** Read the named DATA input port of the host node. */
    data class HostPort(val port: PortName) : ValueSource

    /** Read the named value node on demand. */
    data class Value(val typeId: NodeTypeId) : ValueSource

    companion object {
        private const val HOST_PREFIX = "in:"
        private const val VALUE_PREFIX = "val:"

        /** The spec selecting the comparison's own wired `source` port. */
        const val WIRED_SPEC = ""

        /** The spec string that selects the host's [port]. */
        fun hostSpec(port: PortName): String = "$HOST_PREFIX${port.value}"

        /** The spec string that selects the value node [typeId]. */
        fun valueSpec(typeId: NodeTypeId): String = "$VALUE_PREFIX${typeId.value}"

        /** Parses a persisted source spec. A blank spec means [Wired]. */
        fun parse(spec: String): ValueSource = when {
            spec.isBlank() -> Wired
            spec.startsWith(HOST_PREFIX) -> HostPort(PortName(spec.removePrefix(HOST_PREFIX)))
            spec.startsWith(VALUE_PREFIX) -> Value(NodeTypeId(spec.removePrefix(VALUE_PREFIX)))
            // A bare port name is how an attached gate named a host input before
            // specs were prefixed. Read it as one rather than failing outright.
            else -> HostPort(PortName(spec))
        }
    }
}
