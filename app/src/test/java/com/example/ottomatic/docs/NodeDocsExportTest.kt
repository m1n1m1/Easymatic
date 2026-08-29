package com.example.ottomatic.docs

import com.example.ottomatic.domain.model.NodeTypeDefinition
import com.example.ottomatic.domain.registry.ConfigField
import com.example.ottomatic.domain.registry.ConfigFieldType
import com.example.ottomatic.domain.registry.ConfigSchemaRegistry
import com.example.ottomatic.domain.registry.NodeTypeRegistry
import com.example.ottomatic.domain.registry.PluginNodes
import com.example.ottomatic.engine.ai.NodeCatalog
import com.example.ottomatic.feature.i18n.slug
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Exports every node declaration to `docs/nodes.generated.json`, and guards that the
 * export is up to date — one class, because the generator and the check must agree by
 * construction. The same shape as `NodeStringsSyncTest`, for the reason its KDoc gives:
 * a Gradle task cannot read the declarations, because they are in `:app`'s main source
 * set and reading them means compiling `:app`.
 *
 * ## What this file is for
 *
 * It is one half of the node documentation source. The other half is `docs/nodes/`, one
 * plain-CommonMark file per node, written by hand. The split is **prose versus facts**,
 * not app versus web:
 *
 *  - a node's ports, config rows, permissions and one-line description *are* the code,
 *    and any hand-copy of them drifts the moment a config class changes. So they are
 *    exported from here and written down nowhere else;
 *  - the multi-paragraph explanation is prose, which is unauthorable as a Kotlin string
 *    literal and an escaping trap as an Android string resource. So it is a markdown file.
 *
 * Those prose files follow a fixed section order — opening, `## Working of <Node>`,
 * `## Example`, node-specific sections, `## Points to Remember` — which `docs/ADDING_NODES.md`
 * sets out along with the register they are written in. Only the CommonMark subset below is
 * enforced here; the shape is a convention, because a heading order is not worth a test
 * that would fail on the one node the order does not suit.
 *
 * The website composes the two into a page. The app will later render the same prose file
 * on the config sheet, which is why those files may not contain JSX or anything else
 * outside the subset `prose files stay within the renderable subset` pins.
 *
 * ## Two things that would break the guard
 *
 * **Nothing time-varying may enter the JSON** — no timestamp, no git SHA, no map whose
 * iteration order is not the declaration's. Any of them fails the byte compare on every
 * run and trains everyone to pass the regenerate flag reflexively, which is the same as
 * having no guard.
 *
 * `prettyPrint`'s indentation is a kotlinx implementation detail, so a version bump can
 * reformat the file and fail this on a day nothing about nodes changed. That is accepted:
 * hand-rolling a JSON writer to dodge a once-a-year whitespace diff is the worse trade,
 * and the failure is loud and obvious from the diff.
 *
 * Run with `-PregenerateNodeDocs=true` to rewrite the export; without the flag it compares.
 */
class NodeDocsExportTest {

    private val nodes = NodeTypeRegistry.all
        // Plugin nodes are documented by whoever wrote the plugin. They are also absent from
        // a unit-test JVM — but `PluginNodes` is an `object` with a `reset()` seam in a shared
        // JVM, so this is protection against test-ordering leakage rather than a restatement
        // of intent.
        .filter { PluginNodes.byId(it.typeId) == null }
        .map { it.toDoc() }

    @Test
    fun `export matches the declarations`() {
        // The failure this guards is a future `workingDir` change making `..` the repo's
        // *parent*, where every read answers "absent" and every guard below silently passes.
        assertTrue(
            "docs/ is not where this test expects it. Check the Test task's workingDir.",
            File("../docs/ADDING_NODES.md").exists(),
        )

        val json = JSON.encodeToString(NodeDocsExport(nodes.size, nodes)) + "\n"
        if (System.getProperty("ottomatic.docs.regenerate") == "true") {
            EXPORT.writeText(json)
            return
        }
        assertEquals(HINT, json, EXPORT.readNormalised())
    }

    @Test
    fun `no prose file names a node that is gone`() {
        val known = nodes.map { it.typeId }.toSet()
        val orphans = proseFiles().map { it.nameWithoutExtension }.filterNot { it in known }
        assertTrue(
            "docs/nodes/ documents nodes that no longer exist: $orphans. The filename is the " +
                "only key these files carry, so a renamed typeId leaves one behind.",
            orphans.isEmpty(),
        )
    }

    @Test
    fun `every node has a prose file`() {
        val written = proseFiles().map { it.nameWithoutExtension }.toSet()
        val todo = TODO_LIST.readNormalised().lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .toSet()

        // Two-sided, so a node that gains prose must be struck off. The list only ever
        // shrinks — the same ratchet `HardcodedFeatureStringTest` runs.
        assertEquals(
            "Nodes with no docs/nodes/<typeId>.md that are not on the todo list. Write the " +
                "file, or add it to ${TODO_LIST.path} if you are not doing that now.",
            emptyList<String>(),
            nodes.map { it.typeId }.filterNot { it in written || it in todo }.sorted(),
        )
        assertEquals(
            "These sit on the todo list but now have a prose file. Delete their lines from " +
                "${TODO_LIST.path} — the list only ever shrinks.",
            emptyList<String>(),
            todo.filter { it in written }.sorted(),
        )
    }

    /**
     * Which undocumented nodes to write next.
     *
     * Not an assertion, because a hard rule here would gate the first commit behind twenty
     * essays. It is a standing report instead: a node whose ports move with its wiring is
     * the one the generated table serves worst — the table can only print what is
     * *declared*, and for these that is not what the user sees. Their pages do say so (the
     * generator prints `NodeCatalog.describe`'s sentence), so they are caveated rather than
     * wrong — but they are where prose buys the most.
     */
    @Test
    fun `report which undocumented nodes matter most`() {
        val written = proseFiles().map { it.nameWithoutExtension }.toSet()
        val priority = nodes.filter { it.hasDynamicPorts && it.typeId !in written }.map { it.typeId }
        if (priority.isNotEmpty()) {
            println(
                "[node-docs] ${priority.size} node(s) with dynamic ports still have no prose. " +
                    "Write these before the rest:\n  " + priority.joinToString("\n  "),
            )
        }
    }

    @Test
    fun `prose files stay within the renderable subset`() {
        val offences = proseFiles().flatMap { file ->
            var inFence = false
            file.readNormalised().lines().mapIndexedNotNull { index, line ->
                if (line.trimStart().startsWith("```")) {
                    inFence = !inFence
                    null
                } else if (inFence) {
                    null
                } else {
                    BANNED.firstOrNull { (_, pattern) -> pattern.containsMatchIn(line) }
                        ?.let { (what, _) -> "${file.name}:${index + 1}: $what" }
                }
            }
        }
        assertTrue(
            "The prose is rendered by the Android app as well as by the website, and the app's " +
                "renderer only handles a subset. Offending lines:\n" + offences.joinToString("\n"),
            offences.isEmpty(),
        )
    }

    @Test
    fun `prose does not restate the one-line description`() {
        val byId = nodes.associateBy { it.typeId }
        val restated = proseFiles().filter { file ->
            val description = byId[file.nameWithoutExtension]?.description ?: return@filter false
            val opening = file.readNormalised().lineSequence().firstOrNull { it.isNotBlank() }
            opening?.trim()?.trimEnd('.').equals(description.trimEnd('.'), ignoreCase = true)
        }
        assertTrue(
            "These prose files open by repeating the node's one-line description, which the page " +
                "already prints above them: ${restated.map { it.name }}. The description lives in " +
                "the declaration; the prose starts where it leaves off.",
            restated.isEmpty(),
        )
    }

    private companion object {

        // A Gradle Test task's working directory is the module directory, so `..` is the repo
        // root. The landmark assertion in the first test is what keeps that honest.
        val EXPORT = File("../docs/nodes.generated.json")
        val PROSE_DIR = File("../docs/nodes")
        val TODO_LIST = File("src/test/resources/node-docs-todo.txt")

        val JSON = Json {
            prettyPrint = true
            encodeDefaults = true
        }

        /**
         * Markdown the app's renderer will not be taught.
         *
         * Tables are the expensive construct in Compose, and the facts tables are generated
         * anyway. An h1 would double a title both surfaces already print from `displayName`.
         * The rest are cheap to ban and would each cost inline-parser complexity with nowhere
         * to pay for itself.
         */
        val BANNED: List<Pair<String, Regex>> = listOf(
            "h1 heading (the title comes from displayName)" to Regex("^# "),
            "table" to Regex("""^\s*\|"""),
            "image" to Regex("""!\["""),
            "raw HTML" to Regex("<[a-zA-Z/]"),
            "ordered list" to Regex("""^\s*\d+\. """),
            "nested list" to Regex("""^\s+[-*+] """),
            "horizontal rule" to Regex("""^---+\s*$"""),
            "underscore emphasis (use bold instead)" to Regex("""(?<![\w])_[^_]+_(?![\w])"""),
            "star or plus bullet (use '-')" to Regex("""^\s*[*+] """),
        )

        const val HINT = "docs/nodes.generated.json is stale. Regenerate with:\n" +
            "  .\\gradlew.bat :app:testDebugUnitTest --tests \"*NodeDocsExportTest*\" " +
            "-PregenerateNodeDocs=true"

        fun proseFiles(): List<File> =
            PROSE_DIR.listFiles { file -> file.isFile && file.extension == "md" }
                .orEmpty()
                .sortedBy { it.name }

        /** Line endings normalised, so a `core.autocrlf=true` clone does not fail every compare. */
        fun File.readNormalised(): String =
            if (exists()) readText().replace("\r\n", "\n") else ""
    }
}

@Serializable
private data class NodeDocsExport(val nodeCount: Int, val nodes: List<NodeDoc>)

/**
 * One node, as the documentation needs it.
 *
 * Deliberately not `NodeDeclarationWire`, which exists for a different reader: the wire is
 * what a *plugin* may say, so it carries no permissions and no capabilities and its exec
 * outputs cannot express the host's loop and fork shapes. This carries everything a page
 * needs and nothing a page cannot show.
 */
@Serializable
private data class NodeDoc(
    val typeId: String,
    /** `action.open_url` to `action_open_url`. Ties a page to its string keys and its asset name. */
    val slug: String,
    val displayName: String,
    val description: String,
    val kind: String,
    val category: String,
    /** What the palette heading and the docs sidebar print. */
    val categoryLabel: String,
    val icon: String,
    /**
     * Whether this node's real ports are resolved from the graph rather than declared.
     *
     * The generated table is headed "Declared ports" for every node because of this one, and
     * a page that sets it prints the same sentence `NodeCatalog.describe` prints.
     *
     * It under-reports: `EffectivePorts` dispatches on roughly twenty typeIds where only
     * eleven declarations set the flag. Treat `true` as certain and `false` as merely
     * probable, and never let it gate correctness.
     */
    val hasDynamicPorts: Boolean,
    val ports: List<PortDoc>,
    val configFields: List<ConfigFieldDoc>,
    val permissions: List<PermissionDoc>,
    val capabilities: List<String>,
)

@Serializable
private data class PortDoc(
    val name: String,
    val label: String,
    val kind: String,
    val direction: String,
    /**
     * The item type as prose, or null for an execution port.
     *
     * Rendered by `NodeCatalog.typeNameOf` rather than serialised structurally: `ItemSchema`
     * is a sealed tree with no cycle guard, and a self-referential struct would recurse
     * forever. Borrowing the AI catalog's renderer also makes the type the website prints
     * identical to the one the assistant reads.
     */
    val type: String? = null,
)

@Serializable
private data class ConfigFieldDoc(
    val key: String,
    val label: String,
    /** Absent for a field whose name already says it; see `Hint`. */
    val hint: String? = null,
    /**
     * The field type's name only — `PICKER`, not `PICKER(kind=HA_ENTITY, optional=true)`.
     *
     * `ConfigFieldType` is a sealed interface whose data-class members embed their payload in
     * `toString()`, which is unstable formatting to key a table on.
     */
    val type: String,
    val defaultValue: String,
    val visibleWhen: VisibilityDoc? = null,
    /** Populated for `ENUM` only. Every other option set is the user's own data. */
    val options: List<OptionDoc> = emptyList(),
    /**
     * The chooser behind a field whose answers are not a closed set — a picker kind, a
     * suggestion source, an intent action.
     *
     * The *kind* of chooser, never its contents: a picker's options are the user's own
     * geofence places and Home Assistant entities, which are not this repo's to publish and
     * would not be the reader's anyway.
     */
    val chooser: String? = null,
)

@Serializable
private data class OptionDoc(val value: String, val label: String)

@Serializable
private data class VisibilityDoc(val key: String, val values: List<String>)

@Serializable
private data class PermissionDoc(
    val key: String,
    val type: String,
    val manifestPermission: String? = null,
    /** A noun phrase that fits inside "'Launch App' needs ___" — `PermissionRequirement.label`. */
    val label: String,
)

private fun NodeTypeDefinition.toDoc(): NodeDoc = NodeDoc(
    typeId = typeId.value,
    slug = typeId.slug(),
    displayName = displayName,
    description = description,
    kind = kind.name,
    category = category.name,
    categoryLabel = category.displayName,
    icon = icon.name,
    hasDynamicPorts = hasDynamicPorts,
    ports = ports.map {
        PortDoc(
            name = it.name.value,
            label = it.label,
            kind = it.kind.name,
            direction = it.direction.name,
            type = it.schema?.let(NodeCatalog::typeNameOf),
        )
    },
    // The *derived* registry, not an annotation scan: by the time a `ConfigField` exists,
    // `NodeSchema.labelOr` has already chosen between `@Label` and `prettify(propertyName)`,
    // and a scan would silently miss every prettified label. `NodeStringsSyncTest` reads it
    // here for the same reason.
    configFields = ConfigSchemaRegistry.byId(typeId)?.fields.orEmpty().map { it.toDoc() },
    permissions = permissionRequirements.map {
        PermissionDoc(
            key = it.key,
            type = it.type.name,
            manifestPermission = it.manifestPermission,
            label = it.label,
        )
    },
    capabilities = capabilities.map { it.name },
)

private fun ConfigField<*>.toDoc(): ConfigFieldDoc = ConfigFieldDoc(
    key = key.value,
    label = label,
    hint = hint.takeIf { it.isNotBlank() },
    type = type::class.simpleName.orEmpty(),
    defaultValue = defaultValue,
    visibleWhen = visibleWhen?.let { VisibilityDoc(it.key.value, it.values.sorted()) },
    options = (type as? ConfigFieldType.ENUM)?.options.orEmpty()
        .map { OptionDoc(it.value, it.label) },
    // An explicit `when` rather than a `toString()`, and deliberately partial. What is absent
    // is absent on purpose: the remaining payloads are scoping rules and wire details that say
    // nothing to a reader. Add one here only when a page will print it.
    chooser = when (val fieldType = type) {
        is ConfigFieldType.PICKER -> fieldType.kind.name
        is ConfigFieldType.SUGGESTED -> fieldType.source.name
        is ConfigFieldType.PLUGIN_CHOICE -> fieldType.source
        is ConfigFieldType.INTENT_CHOICE -> fieldType.action
        else -> null
    },
)
