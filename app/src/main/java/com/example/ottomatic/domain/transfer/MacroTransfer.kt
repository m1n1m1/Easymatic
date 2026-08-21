package com.example.ottomatic.domain.transfer

import com.example.ottomatic.domain.model.PhoneRef
import com.example.ottomatic.domain.model.VariableRef
import com.example.ottomatic.domain.model.Workflow
import com.example.ottomatic.domain.model.WorkflowNode
import com.example.ottomatic.domain.model.config.PickerKind
import com.example.ottomatic.domain.model.reissuedConfig
import com.example.ottomatic.domain.model.strippedConfig
import com.example.ottomatic.domain.registry.phoneRefKeys
import com.example.ottomatic.domain.registry.pickerRefKeys
import com.example.ottomatic.nodeapi.plugin.PluginLimits

/**
 * The pure halves of macro transfer: what a graph points at, what an export of it
 * says, and what an import of one becomes.
 *
 * Everything here is a function of its arguments, the way `repairVariableRefs` is,
 * and for the same reason — the project carries no Robolectric, so anything that
 * reaches a repository or a `Context` is a thing no unit test can call. The lookups
 * those ids need, and the writes an import performs, live in
 * `data/MacroTransferRepository`.
 */

/** The outside-the-file things a graph names, gathered in one walk. */
data class ReferencedIds(
    val globalVariables: Set<String> = emptySet(),
    val places: Set<String> = emptySet(),
    val nfcTags: Set<String> = emptySet(),
    val apps: Set<String> = emptySet(),
    val plugins: Set<String> = emptySet(),
    val setup: Set<String> = emptySet(),
)

/**
 * Every reference [workflow] makes to something outside its own file.
 *
 * Derived from each node's declared config schema rather than from a list of node
 * types, so a node that starts taking a geofence place is covered by export the
 * moment it declares `@Picker(PickerKind.GEOFENCE_PLACE)` — the same single
 * registration step the validator and the repair passes already rely on.
 *
 * A blank value is skipped everywhere: an unconfigured picker names nothing, and
 * bundling the empty string would put a phantom entry in the file.
 */
fun referencedIds(workflow: Workflow): ReferencedIds {
    val globals = mutableSetOf<String>()
    val places = mutableSetOf<String>()
    val tags = mutableSetOf<String>()
    val apps = mutableSetOf<String>()
    val plugins = mutableSetOf<String>()
    val setup = mutableSetOf<String>()

    for (node in workflow.nodes) {
        pluginPackageOf(node)?.let(plugins::add)

        // A variable ref is the one picker whose value is a *spec* rather than a bare
        // id, because a local and a global one are the same field. Only the global
        // half is a reference out of the file — a local declaration is already in it.
        node.values(PickerKind.VARIABLE)
            .mapNotNull { VariableRef.parse(it) as? VariableRef.Global }
            .forEach { globals += it.id }

        places += node.values(PickerKind.GEOFENCE_PLACE)
        tags += node.values(PickerKind.NFC_TAG)
        apps += node.values(PickerKind.APP)
        apps += node.values(PickerKind.APP_FILTER)

        setup += node.setupNeeds()
    }
    return ReferencedIds(globals, places, tags, apps, plugins, setup)
}

/** The non-blank values of this node's config fields chosen from the [kind] chooser. */
private fun WorkflowNode.values(kind: PickerKind): List<String> =
    pickerRefKeys(typeId, kind).mapNotNull { config[it]?.takeIf(String::isNotBlank) }

/**
 * The credentialed libraries this node depends on, as [SetupNeed] keys.
 *
 * These are the references that deliberately do **not** travel: each names an entry
 * whose secret is sealed under the Keystore, so a copy would either be useless or be
 * a leak. What can be carried across is the *fact* that one is needed.
 *
 * A `contact:` phone ref is here on the different ground that it names a row in this
 * phone's address book; a literal number is not a reference at all and is skipped,
 * which is why this reads the parsed [PhoneRef] rather than the raw string.
 */
private fun WorkflowNode.setupNeeds(): Set<String> {
    val needs = mutableSetOf<String>()
    if (values(PickerKind.AI_MODEL).isNotEmpty()) needs += SetupNeed.AI_MODEL
    if (HUB_PICKERS.any { values(it).isNotEmpty() }) needs += SetupNeed.SMART_HOME_HUB
    if (values(PickerKind.MAIL_ACCOUNT).isNotEmpty()) needs += SetupNeed.MAIL_ACCOUNT
    if (CALENDAR_PICKERS.any { values(it).isNotEmpty() }) needs += SetupNeed.CALENDAR
    if (values(PickerKind.SOUND).isNotEmpty()) needs += SetupNeed.SOUND
    if (phoneRefKeys(typeId).any { PhoneRef.parse(config[it].orEmpty()) is PhoneRef.Contact }) {
        needs += SetupNeed.CONTACT
    }
    return needs
}

/**
 * Every picker whose value names something on a hub, and therefore something the
 * recipient has to pair or sign into themselves.
 *
 * [PickerKind.HA_TRIGGER] is deliberately absent, exactly as it is from
 * `PickerRefFields`' `HUB_SCOPED_PICKERS`: its value is Home Assistant's own bare
 * trigger id and names no hub at all, so including it would report a hub requirement
 * for a node that has one only if some *other* field on it says so — and if one does,
 * that field is already in this set.
 */
private val HUB_PICKERS = setOf(
    PickerKind.LIGHT_TARGET,
    PickerKind.LIGHT_SCENE,
    PickerKind.HA_ENTITY,
    PickerKind.HA_SERVICE,
    PickerKind.HA_HUB,
    PickerKind.MQTT_BROKER,
)

private val CALENDAR_PICKERS = setOf(PickerKind.CALENDAR, PickerKind.CALENDAR_FILTER)

/**
 * The package of the plugin that declares [node]'s type, or null when the app itself
 * does.
 *
 * Read back out of the typeId rather than looked up, because the typeId is the *only*
 * thing about a plugin a workflow file holds — everything else about one arrives over
 * the binder at discovery time. That is also what makes an uninstalled plugin a
 * recoverable state rather than a lost node: the string still says what to install.
 */
fun pluginPackageOf(node: WorkflowNode): String? {
    val id = node.typeId.value
    if (!PluginLimits.isPluginTypeId(id)) return null
    return id.removePrefix(PLUGIN_SCHEME)
        .substringBefore(TYPE_ID_SEPARATOR)
        .takeIf { it.isNotBlank() }
}

private const val PLUGIN_SCHEME = "plugin:"
private const val TYPE_ID_SEPARATOR = "/"

/**
 * [workflow] packaged for export: credentials stripped, arming forgotten, version
 * stated.
 *
 * Three things are deliberately *not* what the file on disk says.
 *
 * `enabled` is forced false here as well as on import. Belt and braces, but cheap, and
 * the two guard different things: this one keeps the exporter from publishing the fact
 * that they had it running, which is a small privacy leak in a file they are about to
 * send somebody.
 *
 * [strippedConfig] removes the one bearer credential a graph can hold. Its pair,
 * [reissuedConfig], mints a replacement on the way back in, so a round trip yields a
 * working trigger at a *different* endpoint.
 *
 * [Workflow.schemaVersion] is restated rather than left to its default, because the
 * encoder that writes this file sets `encodeDefaults = true` for exactly this key.
 */
fun exportOf(
    workflow: Workflow,
    bundled: BundledLibrary,
    requires: Requirements,
    appVersion: String = "",
): MacroExport = MacroExport(
    format = MACRO_EXPORT_FORMAT,
    schemaVersion = Workflow.CURRENT_SCHEMA_VERSION,
    appVersion = appVersion,
    workflow = workflow.copy(
        schemaVersion = Workflow.CURRENT_SCHEMA_VERSION,
        enabled = false,
        nodes = workflow.nodes.map { it.copy(config = strippedConfig(it)) },
    ),
    bundled = bundled,
    requires = requires,
)

/** What reading an export file produced. */
sealed interface ImportResult {

    /** The macro is ready to be written, under a fresh id and disarmed. */
    data class Ready(val workflow: Workflow, val export: MacroExport) : ImportResult

    /** Written by a newer Ottomatic than this one, and cannot be read. */
    data class TooNew(val formatVersion: Int) : ImportResult

    /**
     * Written against an older graph schema.
     *
     * Refused rather than migrated, following `WorkflowRepository`'s own rule and its
     * reasoning: config keys and port names are derived from node config classes, so a
     * stale file's keys no longer address anything, and quietly substituting defaults
     * would produce a macro that looks fine and does something else. The difference
     * here is that the refusal can be *reported* — a file the user chose is a file they
     * can be told about, where a file on disk is discarded in silence.
     */
    data class TooOld(val schemaVersion: Int) : ImportResult

    /** Not an Ottomatic macro at all, or damaged past decoding. */
    data object Unreadable : ImportResult
}

/**
 * Turns a decoded [export] into the workflow to save, under [newId].
 *
 * The workflow id is the only id replaced. Node and connection ids are workflow-local
 * — nothing outside this file addresses one — so a collision across files is not
 * possible, and re-minting them would only make a re-export impossible to diff against
 * its original.
 *
 * The macro always lands **disarmed**, whatever the file says. A macro can send
 * messages, place calls and spend money; arming a stranger's on open is not a default
 * to have, and the one thing the user unambiguously did ask for is to import it.
 */
fun importOf(export: MacroExport, newId: String): ImportResult = when {
    export.formatVersion > MACRO_EXPORT_VERSION -> ImportResult.TooNew(export.formatVersion)
    export.schemaVersion < Workflow.CURRENT_SCHEMA_VERSION ->
        ImportResult.TooOld(export.schemaVersion)
    else -> ImportResult.Ready(
        workflow = export.workflow.copy(
            id = newId,
            schemaVersion = Workflow.CURRENT_SCHEMA_VERSION,
            enabled = false,
            nodes = export.workflow.nodes.map { it.copy(config = reissuedConfig(it)) },
        ),
        export = export,
    )
}
