package com.example.ottomatic.domain.model

/**
 * Which variable a node means: one of this workflow's own, or a global one.
 *
 * Rather than two config properties or two node types, both are one *ref spec*
 * string held in the node's config and parsed here — the shape [ValueSource] already
 * uses for `val:<typeId>` and [PortSpec] for `name:TYPE`. The spec is persisted, so
 * the prefixes are part of the workflow format:
 *
 *  - `<id>`    → [Local], a declaration in the workflow's own `variables`
 *  - `g:<id>`  → [Global], a declaration in the shared library
 *
 * Two things differ from [ValueSource] deliberately, and both are load-bearing.
 *
 * [parse] returns **null** for a blank spec, where [ValueSource.parse] fails closed
 * to a safe default. A comparison always has *some* source, so a default is right
 * there; a variable field genuinely has the state "nothing chosen yet", and every
 * node that reads one already branches on it to say so in the log rather than
 * quietly writing to a variable nobody named.
 *
 * The persisted spec and the [storeKey] are **different strings**. A spec must not
 * embed the workflow id — a config value naming its own file would have to be
 * rewritten by anything that ever copies a workflow — while the key must, because
 * that is the whole point of a local variable, and because sweeping a deleted
 * workflow's values is a prefix match.
 *
 * Ids, never names: renaming a variable then changes nothing but the declaration.
 * This lives in `domain` because the store writes keys, the engine reads them and
 * the editor writes specs, and `domain` is the only package all three may depend on.
 */
sealed interface VariableRef {

    val id: String

    /** A declaration in the referring workflow's own [Workflow.variables]. */
    data class Local(override val id: String) : VariableRef

    /** A declaration in the shared global library. */
    data class Global(override val id: String) : VariableRef

    companion object {
        /** Marks a global ref, in both the spec and the store key. */
        const val GLOBAL_PREFIX = "g:"

        /** Marks a workflow-scoped store key. Never appears in a persisted spec. */
        const val LOCAL_PREFIX = "w:"

        /** The spec a config field stores for [ref]. */
        fun spec(ref: VariableRef): String = when (ref) {
            is Global -> "$GLOBAL_PREFIX${ref.id}"
            is Local -> ref.id
        }

        /** The spec for a local declaration [id]. */
        fun localSpec(id: String): String = id

        /** The spec for a global declaration [id]. */
        fun globalSpec(id: String): String = "$GLOBAL_PREFIX$id"

        /**
         * Parses a persisted ref spec, or null when nothing is chosen.
         *
         * A blank spec is the honest "no variable configured" that a freshly placed
         * node has. Anything else is a ref: a `g:` prefix names the library, and a
         * bare id names this workflow's own.
         */
        fun parse(spec: String): VariableRef? = when {
            spec.isBlank() -> null
            spec.startsWith(GLOBAL_PREFIX) -> Global(spec.removePrefix(GLOBAL_PREFIX))
            else -> Local(spec)
        }

        /**
         * The key [ref] occupies in the value store, seen from [workflowId].
         *
         * Ids are UUIDs and already unique, so the workflow id in a local key is
         * redundant for lookup — it is there so a deleted workflow's values can be
         * swept by prefix, and so the file on disk says which macro a value belongs
         * to rather than being a wall of anonymous ids.
         */
        fun storeKey(ref: VariableRef, workflowId: String): String = when (ref) {
            is Global -> "$GLOBAL_PREFIX${ref.id}"
            is Local -> "$LOCAL_PREFIX$workflowId:${ref.id}"
        }

        /** The prefix every value belonging to [workflowId] shares. */
        fun scopePrefix(workflowId: String): String = "$LOCAL_PREFIX$workflowId:"
    }
}
