package io.github.m1n1m1.easymatic.feature.i18n

import io.github.m1n1m1.easymatic.core.model.NodeTypeId
import io.github.m1n1m1.easymatic.domain.model.config.ValueType
import io.github.m1n1m1.easymatic.domain.registry.ConfigField
import io.github.m1n1m1.easymatic.domain.registry.ConfigFieldType
import io.github.m1n1m1.easymatic.domain.registry.ConfigSchemaRegistry
import io.github.m1n1m1.easymatic.domain.registry.NodeSchema
import io.github.m1n1m1.easymatic.domain.registry.NodeTypeRegistry
import io.github.m1n1m1.easymatic.domain.registry.PluginNodes
import io.github.m1n1m1.easymatic.domain.registry.enumConfigOptions
import kotlinx.serialization.serializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** One translatable string lifted out of a declaration. [source] names it in errors. */
private data class StringEntry(val key: String, val english: String, val source: String)

/**
 * Generates `strings_nodes.xml` and `NodeStringIds.kt`, and guards that they are up
 * to date — one class, because the generator and the check must agree by construction.
 *
 * A Gradle task cannot do this: the declarations are in `:app`'s main source set, so
 * reading them means compiling `:app`, which needs `R`, which would need the generated
 * XML. So the two files are committed sources and this test keeps them honest. Run it
 * with `-PregenerateNodeStrings=true` to rewrite them; without the flag it compares.
 */
class NodeStringsSyncTest {

    private val entries = nodeEntries()

    @Test
    fun `generated files match the declarations`() {
        if (System.getProperty("easymatic.i18n.regenerate") == "true") {
            XML.writeText(toXml(entries))
            IDS.writeText(toIdsKt(entries))
            return
        }
        assertEquals(HINT, toXml(entries), XML.readNormalised())
        assertEquals(HINT, toIdsKt(entries), IDS.readNormalised())
    }

    @Test
    fun `keys are unique and legal resource names`() {
        val duplicates = entries.groupBy { it.key }
            .filterValues { it.size > 1 }
            .map { (key, made) -> "$key <- ${made.joinToString(" and ") { it.source }}" }
        assertTrue("Two declarations produced one key: $duplicates", duplicates.isEmpty())

        val illegal = entries.filterNot { LEGAL_NAME.matches(it.key) }.map { it.key }
        assertTrue("Not a legal resource name: $illegal", illegal.isEmpty())
    }

    private companion object {

        // A Gradle Test task's working directory is the module directory.
        val XML = File("src/main/res/values/strings_nodes.xml")
        val IDS = File("src/main/java/io/github/m1n1m1/easymatic/feature/i18n/NodeStringIds.kt")

        val LEGAL_NAME = Regex("^[a-z][a-z0-9_]*$")

        const val HINT = "Generated node strings are stale. Regenerate with:\n" +
            "  .\\gradlew.bat :app:testDebugUnitTest --tests \"*NodeStringsSyncTest*\" " +
            "-PregenerateNodeStrings=true"
    }
}

/**
 * Every open-ended piece of node text, keyed.
 *
 * Text whose answer set is a *closed enum* is deliberately absent — `NodeCategory`
 * and the permission copy take the direct `R.string` route instead, which is
 * compile-checked. See "Node text and translation" in CLAUDE.md.
 *
 * Plugin nodes are filtered out: their text arrives over the binder already rendered
 * by its author, so a key here would name a string no translator could ever supply.
 */
private fun nodeEntries(): List<StringEntry> =
    NodeTypeRegistry.all
        .filter { PluginNodes.byId(it.typeId) == null }
        .flatMap { definition ->
            val slug = definition.typeId.slug()
            val id = definition.typeId.value
            listOf(
                StringEntry("node_${slug}_name", definition.displayName, "$id displayName"),
                StringEntry("node_${slug}_desc", definition.description, "$id description"),
            ) + definition.ports.map { port ->
                StringEntry(
                    key = "port_${slug}_${port.name.value.snake()}",
                    english = port.label,
                    source = "$id port ${port.name.value}",
                )
            } + configEntries(definition.typeId, slug, id)
        } + valueTypeEntries() + unsetOptionEntry()

/**
 * A node's form rows and their dropdown choices.
 *
 * Read from the **derived** `ConfigSchemaRegistry` rather than from `@Label`
 * annotations, and that is the load-bearing choice: by the time a `ConfigField`
 * exists, `NodeSchema.labelOr` has already picked between the annotation and
 * `prettify(propertyName)`, and the two are indistinguishable from here. An
 * annotation scan would silently miss every prettified label and leave `prettify`'s
 * structural English-only-ness in place.
 */
private fun configEntries(typeId: NodeTypeId, slug: String, id: String): List<StringEntry> {
    val schema = ConfigSchemaRegistry.byId(typeId) ?: return emptyList()
    return schema.fields.flatMap { field ->
        val key = field.key.value.snake()
        val options = (field.type as? ConfigFieldType.ENUM)?.options.orEmpty()
        listOf(
            StringEntry("cfg_${slug}_$key", field.label, "$id config ${field.key.value}"),
        ) + hintEntry(field, slug, key, id) + options
            // The blank "not set" choice shares one key across every field — see
            // NodeText.optionLabel.
            .filterNot { it.value.isBlank() }
            .map {
                StringEntry(
                    key = "opt_${slug}_${key}_${it.value.snake()}",
                    english = it.label,
                    source = "$id option ${field.key.value}=${it.value}",
                )
            }
    }
}

/**
 * A field's explanation, listed only when it has one.
 *
 * Absent rather than blank for a field whose name already says it: an empty `<string>` in eight
 * locales is eight invitations to translate nothing, and [NodeText.fieldHint] falls back to the
 * declaration anyway.
 */
private fun hintEntry(
    field: ConfigField<*>,
    slug: String,
    key: String,
    id: String,
): List<StringEntry> = if (field.hint.isBlank()) {
    emptyList()
} else {
    listOf(StringEntry("hint_${slug}_$key", field.hint, "$id hint ${field.key.value}"))
}

/**
 * The `@Ports` row type dropdown, which belongs to no node.
 *
 * The wildcard row is included even though it is not a [ValueType]: it sits in the
 * same dropdown, and leaving it out would translate every row but one.
 */
private fun valueTypeEntries(): List<StringEntry> =
    enumConfigOptions(serializer<ValueType>().descriptor).map {
        StringEntry("valuetype_${it.value.snake()}", it.label, "ValueType.${it.value}")
    } + StringEntry("valuetype_any", "Anything", "PortSpec.ANY")

/** The "not set" choice every nullable enum field offers, spelled once. */
private fun unsetOptionEntry(): List<StringEntry> =
    listOf(StringEntry("config_option_unset", NodeSchema.UNSET_LABEL, "NodeSchema.UNSET_LABEL"))

private fun toXml(entries: List<StringEntry>): String = buildString {
    appendLine("""<?xml version="1.0" encoding="utf-8"?>""")
    appendLine("<!-- GENERATED by NodeStringsSyncTest. Do not edit by hand. -->")
    appendLine("<!-- See \"Node text and translation\" in CLAUDE.md. -->")
    appendLine("<resources>")
    entries.forEach {
        // A '%' in a string Android thinks is formattable is an aapt2 error, and none
        // of this text takes arguments — it is a name, not a sentence with a slot.
        val attribute = if ('%' in it.english) " formatted=\"false\"" else ""
        appendLine("    <string name=\"${it.key}\"$attribute>${it.english.escapedForXml()}</string>")
    }
    appendLine("</resources>")
}

private fun toIdsKt(entries: List<StringEntry>): String = buildString {
    appendLine("// GENERATED by NodeStringsSyncTest. Do not edit by hand.")
    // It is a lookup table with one line per string in the node system, so it is
    // supposed to be large. Splitting it would only spread the same table over files
    // nobody reads either.
    appendLine("@file:Suppress(\"LargeClass\")")
    appendLine()
    appendLine("package io.github.m1n1m1.easymatic.feature.i18n")
    appendLine()
    appendLine("import io.github.m1n1m1.easymatic.R")
    appendLine()
    appendLine("internal object NodeStringIds {")
    appendLine()
    // Naming every id as a literal R.string reference is what stops R8 from stripping
    // them the day `optimization { enable = true }` flips.
    // Explicit types, not inferred: the list is empty between the step that adds a key
    // family and the step that fills it, and `arrayOf()` alone cannot infer from nothing.
    appendLine("    private val KEYS: Array<String> = arrayOf(")
    entries.forEach { appendLine("        \"${it.key}\",") }
    appendLine("    )")
    appendLine()
    appendLine("    private val IDS: IntArray = intArrayOf(")
    entries.forEach { appendLine("        R.string.${it.key},") }
    appendLine("    )")
    appendLine()
    appendLine("    // Two arrays rather than mapOf(): one Pair per entry would put ~30 bytes of")
    appendLine("    // bytecode each into a single <clinit>, nearing its 64 KB limit at full size.")
    appendLine("    val byKey: Map<String, Int> = KEYS.indices.associate { KEYS[it] to IDS[it] }")
    appendLine("}")
}

/** Backslash first, or it doubles the escapes added after it. */
private fun String.escapedForXml(): String = this
    .replace("\\", "\\\\")
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace("'", "\\'")
    .replace("\"", "\\\"")

/**
 * Reads with line endings normalised: a clone under `core.autocrlf=true` checks these
 * files out with CRLF and would fail every comparison for a reason that has nothing
 * to do with the declarations.
 */
private fun File.readNormalised(): String = readText().replace("\r\n", "\n")
