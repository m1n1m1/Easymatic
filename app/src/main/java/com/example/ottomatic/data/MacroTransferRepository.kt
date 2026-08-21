package com.example.ottomatic.data

import com.example.ottomatic.domain.model.Workflow
import com.example.ottomatic.domain.transfer.BundledLibrary
import com.example.ottomatic.domain.transfer.ImportResult
import com.example.ottomatic.domain.transfer.MACRO_EXPORT_FORMAT
import com.example.ottomatic.domain.transfer.MacroExport
import com.example.ottomatic.domain.transfer.Requirements
import com.example.ottomatic.domain.transfer.exportOf
import com.example.ottomatic.domain.transfer.importOf
import com.example.ottomatic.domain.transfer.referencedIds
import java.util.UUID
import kotlinx.serialization.json.Json

/**
 * Reads a macro out to text and back in again: the I/O half of macro transfer, whose
 * pure half is `domain/transfer/MacroTransfer.kt`.
 *
 * Split that way for the reason `repairVariableRefs` is split from the repository that
 * calls it — the decisions (what travels, what is stripped, what a version mismatch
 * means) are worth unit-testing, and this project carries no Robolectric, so anything
 * holding a repository is beyond a JVM test's reach. What is left here is lookups and
 * writes, which is the part there is nothing to decide about.
 */
class MacroTransferRepository(
    private val workflows: WorkflowRepository,
    private val globals: GlobalVariableRepository,
    private val places: GeofencePlaceRepository,
    private val nfcTags: NfcTagRepository,
    private val appVersion: String = "",
) {

    /**
     * The encoder for files that leave this device, and the one place in the app that
     * sets `encodeDefaults = true`.
     *
     * That is not a formatting preference. `WorkflowRepository`'s encoder leaves it at
     * the default `false`, and because [Workflow.schemaVersion]'s own default *is*
     * [Workflow.CURRENT_SCHEMA_VERSION], the key is never written to any file on disk —
     * so the version gate on load reads a missing key as "current" and never fires. A
     * file that has travelled cannot afford that: the app reading it may be older than
     * the app that wrote it, and the whole point of the gate is to say so. Writing every
     * default costs a few kilobytes and buys a file that states its own version.
     */
    private val exportJson = Json {
        prettyPrint = true
        encodeDefaults = true
    }

    /**
     * The decoder for files that arrive, which is lenient in the two ways that matter
     * and strict in none that would refuse a good file.
     *
     * `ignoreUnknownKeys` is the forward-compatibility story a newer exporter relies on.
     * `isLenient` is not set: this is machine-written JSON, and accepting malformed input
     * here would turn "not an Ottomatic file" into a half-decoded macro.
     */
    private val importJson = Json { ignoreUnknownKeys = true }

    /** The text of [workflowId] as an export file, or null when it does not load. */
    suspend fun exportText(workflowId: String): String? {
        val workflow = workflows.load(workflowId) ?: return null
        return exportJson.encodeToString(MacroExport.serializer(), packaged(workflow))
    }

    /** The filename to offer for [name], sanitised to something every file system takes. */
    fun fileNameFor(name: String): String {
        val safe = name.map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '_' }
            .joinToString("")
            .trim('_')
            .take(MAX_NAME)
        return (safe.ifBlank { FALLBACK_NAME }) + FILE_SUFFIX
    }

    /**
     * [workflow] packaged, with the credential-free library entries it points at
     * gathered in beside it.
     *
     * The lookups are all against a snapshot list rather than a query, because each of
     * these repositories keeps its whole library in memory anyway — they are tens of
     * entries, not thousands.
     */
    private fun packaged(workflow: Workflow): MacroExport {
        val refs = referencedIds(workflow)
        return exportOf(
            workflow = workflow,
            bundled = BundledLibrary(
                globals = globals.list().filter { it.id in refs.globalVariables },
                places = places.list().filter { it.id in refs.places },
                nfcTags = nfcTags.list().filter { it.uid in refs.nfcTags },
            ),
            requires = Requirements(
                plugins = refs.plugins.sorted(),
                apps = refs.apps.sorted(),
                setup = refs.setup.sorted(),
            ),
            appVersion = appVersion,
        )
    }

    /**
     * Reads [text] as an export file and, when it is one, saves the macro it holds
     * under a fresh id and adopts whatever library entries came with it.
     *
     * A file with no envelope is accepted as a bare [Workflow] — that is what a file
     * lifted straight out of another phone's `files/workflows/` is, and refusing it
     * would be refusing the app's own format. Such a file carries no `schemaVersion`
     * key (see [exportJson]), so it decodes as current and is taken at its word; there
     * is nothing else it could be compared against.
     */
    suspend fun import(text: String): ImportResult {
        val export = decode(text) ?: return ImportResult.Unreadable
        val result = importOf(export, UUID.randomUUID().toString())
        if (result is ImportResult.Ready) {
            adopt(export.bundled)
            workflows.save(result.workflow)
        }
        return result
    }

    private fun decode(text: String): MacroExport? {
        runCatching { importJson.decodeFromString(MacroExport.serializer(), text) }
            .getOrNull()
            ?.takeIf { it.format == MACRO_EXPORT_FORMAT }
            ?.let { return it }
        // No envelope: a raw workflow file. Only accepted when it actually looks like a
        // graph — every field of `Workflow` has a default, so an empty object and any
        // unrelated JSON object both decode happily into an empty macro, and importing
        // a blank canvas from a photo is worse than refusing it.
        return runCatching { importJson.decodeFromString(Workflow.serializer(), text) }
            .getOrNull()
            ?.takeIf { it.nodes.isNotEmpty() }
            ?.let { MacroExport(MACRO_EXPORT_FORMAT, schemaVersion = it.schemaVersion, workflow = it) }
    }

    /**
     * Puts the bundled entries into their libraries, **keeping their ids and never
     * overwriting one that is already there**.
     *
     * Keeping the id is what makes the imported graph's references resolve without a
     * single config value being rewritten. Not overwriting is the other half of the same
     * care: two people's "Home" are different places, and an import must not move the
     * recipient's. So a colliding id is left alone — the macro then points at the
     * recipient's own entry, which is both the safe outcome and almost always the
     * intended one, since a colliding UUID means it is literally the same entry that
     * left this device earlier.
     */
    private suspend fun adopt(bundled: BundledLibrary) {
        val knownGlobals = globals.list().mapTo(mutableSetOf()) { it.id }
        bundled.globals.filter { it.id !in knownGlobals }.forEach { globals.upsert(it) }

        val knownPlaces = places.list().mapTo(mutableSetOf()) { it.id }
        bundled.places.filter { it.id !in knownPlaces }.forEach { places.upsert(it) }

        val knownTags = nfcTags.list().mapTo(mutableSetOf()) { it.uid }
        bundled.nfcTags.filter { it.uid !in knownTags }.forEach { nfcTags.upsert(it) }
    }

    private companion object {
        /**
         * `.otto.json` rather than a bare `.otto`: the second extension is what makes
         * every file manager, mail client and messenger treat it as text it is willing to
         * carry, and the first is what makes it recognisable as ours in a list of
         * downloads. An unknown extension is the commonest reason a share silently fails.
         */
        const val FILE_SUFFIX = ".otto.json"
        const val FALLBACK_NAME = "macro"
        const val MAX_NAME = 40
    }
}
