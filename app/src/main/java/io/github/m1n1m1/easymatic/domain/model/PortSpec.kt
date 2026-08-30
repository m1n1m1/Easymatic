package io.github.m1n1m1.easymatic.domain.model

import io.github.m1n1m1.easymatic.domain.model.config.ValueType
import io.github.m1n1m1.easymatic.domain.model.schema.ItemSchema

/**
 * One data port a node declares in its own config: a [name] and the [type] that
 * port carries.
 *
 * `action.script` is the only node whose data ports are named by the *user*
 * rather than by its declaration or by an upstream schema. Both sides use this:
 * the inputs the script reads and the outputs it returns are the same idea seen
 * from opposite directions, so they share one spec, one parser and one editor.
 *
 * A null [type] means **Anything** — a [ItemSchema.Wildcard] port that accepts or
 * emits any value, including a whole struct. That is the right default for an
 * input, because handing a script an entire HTTP response is one of the most
 * useful things it can do, and no [ValueType] can say "object". Naming a type
 * instead buys the port a colour and a real type check, which is what makes a
 * mis-wired script a refused drop rather than a confusing `NaN`.
 *
 * [list] is a second, independent axis: *how many* of [type], not which type. It is
 * deliberately not a sixth [ValueType] — that enum is typed `ItemSchema.Primitive`
 * all the way through `convert`, `ComparisonType` and the adaptive transforms, so a
 * `LIST` member would mean widening every one of them to describe something none of
 * them can do anything with. Unreal Blueprints splits the same two questions across
 * two controls on a pin for the same reason, and `ANY[]` — a list of anything — is
 * expressible here only because the axes are separate.
 *
 * The list is persisted as one `name:TYPE` line per port in a single `String`
 * config field, with `[]` appended for a list port (`items:TEXT[]`), for the same
 * reason `CompareConfig.source` persists a [ValueSource] as a string: every config
 * property must be a scalar ([io.github.m1n1m1.easymatic.domain.registry.NodeSchema]
 * rejects a list outright), so a structured setting is stored as text and parsed
 * here. This lives in `domain` because both the runtime and the design-time port
 * derivation ([io.github.m1n1m1.easymatic.domain.registry.effectivePorts]) must read it
 * the same way.
 */
data class PortSpec(val name: String, val type: ValueType?, val list: Boolean = false) {

    /** The port's schema: the named type's, or a wildcard for "Anything", wrapped when [list]. */
    val schema: ItemSchema
        get() = (type?.schema ?: ItemSchema.Wildcard).let { element ->
            if (list) ItemSchema.ListSchema(element) else element
        }

    companion object {
        private const val SEPARATOR = ':'

        /** The persisted marker for a list port, appended to the type name. */
        const val LIST_SUFFIX = "[]"

        /** The persisted spelling of "Anything" — deliberately not a [ValueType]. */
        const val ANY = "ANY"

        /** What the outputs field falls back to, so a node always has something to wire. */
        val DEFAULT_OUTPUT = PortSpec(name = "result", type = ValueType.TEXT)

        /**
         * Ports one side of a script may declare. The node card's width grows
         * with its port count ([io.github.m1n1m1.easymatic.feature.grapheditor.GraphGeometry]),
         * so an unbounded list would produce a node wider than the canvas. Eight
         * is well past what a readable script takes or returns.
         */
        const val MAX_PORTS = 8

        /**
         * Parses a persisted spec: one `name:TYPE` per line.
         *
         * **Never throws.** A line with no type, a type name from a future
         * version, a name that is not an identifier — each is dropped or read as
         * [ANY], because this text is edited a character at a time and the node
         * card is rebuilt from it on every keystroke.
         *
         * May return **empty**, which for inputs is a real answer: a script that
         * reads nothing and returns `Date.now()` is perfectly good. Outputs use
         * [parseOutputs] instead, where empty is not.
         */
        fun parse(raw: String?): List<PortSpec> = raw.orEmpty()
            .lineSequence()
            .mapNotNull(::parseLine)
            .distinctBy { it.name }
            .take(MAX_PORTS)
            .toList()

        /**
         * [parse], but never empty — a node whose card has no output port reads
         * as broken where one named `result` reads as merely unconfigured.
         */
        fun parseOutputs(raw: String?): List<PortSpec> = parse(raw).ifEmpty { listOf(DEFAULT_OUTPUT) }

        /** The persisted form of [specs]; round-trips through [parse]. */
        fun encode(specs: List<PortSpec>): String = specs.joinToString(separator = "\n") { spec ->
            // Always written with its separator, even for an unnamed row: the
            // editor is fully controlled, so a row that encoded to nothing would
            // vanish from under the cursor the moment its name was cleared.
            val suffix = if (spec.list) LIST_SUFFIX else ""
            "${spec.name}$SEPARATOR${spec.type?.name ?: ANY}$suffix"
        }

        /** One editor row's worth of text, keeping names [parse] would reject. */
        fun parseLenient(line: String): PortSpec {
            val trimmed = line.trim()
            val separator = trimmed.lastIndexOf(SEPARATOR)
            if (separator < 0) return PortSpec(trimmed, null)
            val rawType = trimmed.substring(separator + 1).trim()
            // The suffix is stripped before the type is looked up, so `TEXT[]` and
            // `TEXT` reach `parseType` identically and an unrecognised type still
            // degrades to "Anything" rather than losing its list-ness with it.
            val list = rawType.endsWith(LIST_SUFFIX)
            val bare = if (list) rawType.dropLast(LIST_SUFFIX.length) else rawType
            return PortSpec(trimmed.substring(0, separator).trim(), parseType(bare), list)
        }

        private fun parseLine(line: String): PortSpec? =
            // Covers a blank line too — the empty string is not a port name.
            parseLenient(line).takeIf { isPortName(it.name) }

        /** [ANY] and anything unrecognised both mean "no type", i.e. a wildcard. */
        private fun parseType(raw: String): ValueType? =
            runCatching { ValueType.valueOf(raw.trim().uppercase()) }.getOrNull()

        /**
         * Identifier-shaped names only. The name is both a port name and the
         * variable the script sees, so it has to be writable as `{ count: 1 }`
         * and readable as `count` in JavaScript without quoting.
         *
         * Checked character by character rather than with a regex on purpose:
         * Android's ICU engine rejects patterns OpenJDK accepts, and a throwing
         * `Regex` in an initialiser here would take the whole node palette down
         * (see `BuildTextTransform.SLOT`).
         */
        private fun isPortName(name: String): Boolean =
            name.isNotEmpty() &&
                name.length <= MAX_NAME_LENGTH &&
                (name[0].isLetter() || name[0] == '_') &&
                name.all { it.isLetterOrDigit() || it == '_' }

        private const val MAX_NAME_LENGTH = 32
    }
}
