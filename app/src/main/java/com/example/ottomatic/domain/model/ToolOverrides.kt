package com.example.ottomatic.domain.model

/**
 * How one node differs from its model profile about what the AI may do.
 *
 * **A diff and not a copy**, which is the whole design. A profile answers "what may
 * this model ever do" once, for every macro that uses it; a node answers "and here,
 * what should be different". Storing the *effective* list on the node instead would
 * freeze the profile at the moment that node was last edited — adding a tool to the
 * profile later would reach every node except the ones somebody had adjusted, which is
 * exactly the surprise the profile split was meant to remove.
 *
 * Three shapes, one line each:
 *
 * - `action.light_scene {"scene":"sh:…"}` — use this entry, whether or not the profile
 *   grants one. That is both "re-pin it" and "add it", because they are the same thing
 *   from the node's side.
 * - `action.light_scene` — the same with nothing pinned, which is how *"let the AI
 *   choose the scene"* is expressed.
 * - `-action.send_sms` — not here, whatever the profile says.
 *
 * A line **replaces** the profile's entry outright rather than merging pin-by-pin. That
 * is forced rather than chosen: merging has no way to say *remove this pin*, and
 * removing a pin is the thing this exists for.
 *
 * Stored as text in a single `String` config property, for [PortSpec]'s and
 * [ToolSpec]'s reason and by their rule — every config property is a scalar, so a
 * structured setting is persisted as text and parsed here.
 *
 * Separate from [ToolSpec] rather than a mode of it, because [ToolTarget.parse] would
 * read `-action.send_sms` as a node type whose id begins with a hyphen: it contains a
 * dot, so it parses, and it would name a tool that can never resolve. The marker is
 * therefore stripped one level up, here, before [ToolSpec] ever sees the line.
 */
data class ToolOverrides(
    /** What this node uses instead of the profile's entry, by target. */
    val replaced: List<ToolSpec> = emptyList(),
    /** What this node refuses regardless of the profile. */
    val removed: Set<ToolTarget> = emptySet(),
) {

    /** Whether this node has anything to say — a blank field is the ordinary case. */
    val isEmpty: Boolean get() = replaced.isEmpty() && removed.isEmpty()

    /** Whether [target] is spoken for here, in either direction. */
    fun overrides(target: ToolTarget): Boolean =
        removed.contains(target) || replaced.any { it.target == target }

    /**
     * [base] as this node sees it: the profile's list, minus what is removed, with
     * replacements substituted in place and additions appended.
     *
     * **Order is the profile's**, so a replaced entry stays where it was and only a
     * genuinely new one lands at the end. That matters more than it looks: the tool
     * list is sent to the model in this order on every turn, and a list that reshuffled
     * whenever a pin changed would move the cheapest thing to cache.
     */
    fun applyTo(base: List<ToolSpec>): List<ToolSpec> {
        if (isEmpty) return base
        val byTarget = replaced.associateBy { it.target }
        val kept = base
            .filterNot { it.target in removed }
            .map { byTarget[it.target] ?: it }
        val added = replaced.filterNot { override -> base.any { it.target == override.target } }
        return kept + added
    }

    /** The persisted form; round-trips through [parse]. */
    fun encode(): String = (
        replaced.map { it.encode() } + removed.map { "$REMOVED_PREFIX${it.encode()}" }
        ).joinToString(separator = "\n")

    companion object {

        /** Marks a line as "not here", which a typeId can never start with. */
        const val REMOVED_PREFIX = "-"

        /**
         * Parses a persisted override list.
         *
         * **Never throws**, on [ToolSpec.parse]'s rule and for its reason: this text is
         * written by an editor and read back on every frame, and a line naming a node
         * type that no longer exists still parses — deciding that is the catalogue's
         * job, which drops it rather than offering a broken tool.
         *
         * A target named in both directions is **removed**, because the two lines
         * cannot both be honoured and refusing is the safe reading of an ambiguity.
         */
        fun parse(raw: String?): ToolOverrides {
            val lines = raw.orEmpty().lineSequence().map { it.trim() }.filter { it.isNotEmpty() }
            val (removals, replacements) = lines.partition { it.startsWith(REMOVED_PREFIX) }
            val removed = removals
                .mapNotNull { ToolTarget.parse(it.removePrefix(REMOVED_PREFIX)) }
                .toSet()
            val replaced = replacements
                .mapNotNull(ToolSpec::parseLine)
                .filterNot { it.target in removed }
                .distinctBy { it.target }
            return ToolOverrides(replaced = replaced, removed = removed)
        }

        /**
         * The override that turns [base] into [wanted], or nothing when they agree.
         *
         * This is what the editor writes: it is handed the effective list the user just
         * ticked their way to and works out the *difference*, so a row left alone
         * contributes no line at all. Writing the effective list instead would be the
         * copy this class exists to avoid, and a row "reset" to the profile's current
         * value would silently pin that value forever.
         */
        fun between(base: List<ToolSpec>, wanted: List<ToolSpec>): ToolOverrides {
            val baseByTarget = base.associateBy { it.target }
            val wantedByTarget = wanted.associateBy { it.target }
            return ToolOverrides(
                replaced = wanted.filter { baseByTarget[it.target] != it },
                removed = base.map { it.target }.filterNot { it in wantedByTarget }.toSet(),
            )
        }
    }
}
