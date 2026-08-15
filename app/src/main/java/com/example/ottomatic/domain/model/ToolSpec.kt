package com.example.ottomatic.domain.model

import com.example.ottomatic.core.model.ConfigKey
import com.example.ottomatic.core.model.NodeTypeId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject

/**
 * One thing an AI node is allowed to do: a node type or another macro, plus the
 * config the author fixed in advance.
 *
 * **A tool is a node type *plus pinned config*, never a node type alone**, and that
 * is the design decision the whole feature turns on. The obvious alternative — offer
 * `action.light_control` and let the model fill the fields — collides with the app's
 * own rule that identifiers are chosen and not typed: that node's `target` is a
 * [SmartHomeRef] spec (`sh:<hubId>|<kind>|<rid>|<name>`) and `action.ai_prompt`'s
 * connection is a UUID. A model cannot invent either, and a mistyped one does not
 * fail loudly — it names nothing, and the node merely looks broken. That is precisely
 * the failure `@Picker` exists to prevent, and handing those fields to a model
 * re-opens it.
 *
 * So the author pins the opaque fields with the pickers that already exist, and
 * whatever is left over becomes the tool's arguments. "Let the AI dim the kitchen"
 * stays a decision somebody made about *the kitchen*.
 *
 * Persisted as one line per tool in a single `String` config field, for
 * [PortSpec]'s reason and by [PortSpec]'s rule: every config property must be a
 * scalar, so a structured setting is stored as text and parsed here. The pinned map
 * is JSON rather than `key=value` pairs because a pinned value is arbitrary user text
 * — a notification body with an `=` and a newline in it is ordinary — and JSON is the
 * escaping this project already has.
 */
data class ToolSpec(val target: ToolTarget, val pinned: Map<ConfigKey, String> = emptyMap()) {

    /** The persisted form of this one entry; round-trips through [parseLine]. */
    fun encode(): String {
        val head = target.encode()
        if (pinned.isEmpty()) return head
        val json = buildJsonObject {
            // Sorted so the persisted text does not churn on an unrelated edit, which
            // is what makes a workflow file's diff readable.
            pinned.toSortedMap(compareBy { it.value }).forEach { (key, value) ->
                put(key.value, JsonPrimitive(value))
            }
        }
        return "$head $json"
    }

    companion object {

        /**
         * How many tools one model profile may be granted.
         *
         * Not a rendering limit like [PortSpec.MAX_PORTS] — nothing here is drawn on
         * the card — but a *prompt* limit: every tool's name, description and argument
         * schema is sent on every turn, so a long list costs more context than the
         * question and leaves the model choosing badly among things it cannot hold in
         * view at once. The editor says so above roughly twenty.
         *
         * **It is high enough to cover "allow everything", and that is what changed.**
         * It was 16 while a list was built one tool at a time; the permission editor
         * offers a switch that ticks every runnable node at once, and a cap below that
         * number would silently drop whatever fell off the end — so "allow everything"
         * would not have meant it. A test pins that it stays above the runnable count
         * as nodes are added, and the number is round and generous rather than fitted
         * to today's total, because a ceiling reached by an ordinary gesture is one
         * somebody meets by accident.
         *
         * What is left of the prompt-budget argument is a **warning** in the editor
         * well below this, at roughly two dozen, which is where a list starts costing
         * more context than the question. The ceiling is now the runaway guard rather
         * than the advice.
         */
        const val MAX_TOOLS = 128

        private val json = Json { ignoreUnknownKeys = true }

        /**
         * Parses a persisted list: one tool per line.
         *
         * **Never throws**, for [PortSpec.parse]'s reason — this text is written by an
         * editor a keystroke at a time, and a half-typed line must degrade to "not a
         * tool yet" rather than taking the form down. A line naming a target that no
         * longer exists still parses; deciding that is the validator's job, and it is
         * the difference between a warning the user can act on and a tool that
         * silently vanished.
         *
         * **It does not truncate at [MAX_TOOLS], deliberately.** It used to, and that
         * was a silent cap: the editor would show a list the runtime had quietly
         * shortened, and nothing anywhere said which end was dropped. Enforcing the
         * ceiling belongs where it can be *reported* — the editor refuses to add past
         * it, and `NodeToolCatalog` logs whatever it leaves out.
         */
        fun parse(raw: String?): List<ToolSpec> = raw.orEmpty()
            .lineSequence()
            .mapNotNull(::parseLine)
            .distinctBy { it.target }
            .toList()

        /** The persisted form of [specs]; round-trips through [parse]. */
        fun encode(specs: List<ToolSpec>): String =
            specs.joinToString(separator = "\n") { it.encode() }

        /** One line, or null when it names nothing usable. */
        @Suppress("ReturnCount") // Two ways a line names nothing, then the tool it does name.
        fun parseLine(line: String): ToolSpec? {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) return null
            val split = trimmed.indexOf(' ')
            val head = if (split < 0) trimmed else trimmed.substring(0, split)
            val tail = if (split < 0) "" else trimmed.substring(split + 1).trim()
            val target = ToolTarget.parse(head) ?: return null
            return ToolSpec(target = target, pinned = parsePinned(tail))
        }

        /** A malformed or non-object tail pins nothing, rather than losing the tool. */
        @Suppress("ReturnCount") // Blank and malformed both pin nothing, for different reasons.
        private fun parsePinned(raw: String): Map<ConfigKey, String> {
            if (raw.isBlank()) return emptyMap()
            val root = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull()
                ?: return emptyMap()
            return root.readPinned()
        }

        private fun JsonObject.readPinned(): Map<ConfigKey, String> = entries.associate { (key, value) ->
            ConfigKey(key) to ((value as? JsonPrimitive)?.content ?: value.toString())
        }
    }
}

/**
 * What a tool runs.
 *
 * Two members rather than one string with a prefix convention, because the two are
 * resolved against different registries, validated differently and fail differently —
 * a node type that is gone is an app that was downgraded, where a macro that is gone
 * is one the user deleted.
 */
sealed interface ToolTarget {

    /** The persisted spelling. */
    fun encode(): String

    /** One node type, run directly. */
    data class Node(val typeId: NodeTypeId) : ToolTarget {
        override fun encode(): String = typeId.value
    }

    /**
     * One macro, run through its `trigger.api` node.
     *
     * The top tier and the one that costs the author nothing: the macro already has a
     * name, and `trigger.api`'s own `@Ports` spec already declares its typed inputs,
     * so the tool's parameter schema is written before this feature ever looks at it.
     */
    data class Macro(val macroId: String) : ToolTarget {
        override fun encode(): String = "$MACRO_PREFIX$macroId"
    }

    companion object {

        /** Distinguishes a macro id from a node typeId, which never contains a colon. */
        const val MACRO_PREFIX = "macro:"

        fun parse(raw: String): ToolTarget? {
            val trimmed = raw.trim()
            return when {
                trimmed.isEmpty() -> null
                trimmed.startsWith(MACRO_PREFIX) ->
                    trimmed.removePrefix(MACRO_PREFIX).takeIf { it.isNotBlank() }?.let(::Macro)
                // A node typeId is `family.name`; anything with no dot is not one, and
                // accepting it would produce a tool that can never resolve.
                trimmed.contains('.') -> Node(NodeTypeId(trimmed))
                else -> null
            }
        }
    }
}
