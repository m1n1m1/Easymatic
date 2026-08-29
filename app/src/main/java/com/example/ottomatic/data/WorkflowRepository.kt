package com.example.ottomatic.data

import com.example.ottomatic.domain.model.MacroAccent
import com.example.ottomatic.domain.model.MacroIcon
import com.example.ottomatic.domain.model.Workflow
import com.example.ottomatic.domain.model.WorkflowSummary
import com.example.ottomatic.domain.registry.pruneUnknownNodes
import com.example.ottomatic.domain.registry.repairAiRefs
import com.example.ottomatic.domain.registry.repairVariableRefs
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Persists workflows as individual JSON files under a `workflows/` directory in
 * the app's files directory: one file per workflow, named `<id>.json`.
 *
 * Workflows written by an older [Workflow.schemaVersion] are discarded on load
 * rather than migrated: config keys and port names are derived from node config
 * classes, so a stale file's keys no longer address anything and silently
 * falling back to defaults would be worse than starting clean.
 *
 * The list screen uses [list] which only deserialises the lightweight
 * [WorkflowSummary] fields (id/name/enabled) per file, avoiding the cost of
 * decoding every full graph.
 *
 * [globals] is optional so tests can build a repository with no library behind it.
 * When it is present, [load] repairs the variable references of a workflow saved
 * before variables were declared — see [repairVariableRefs].
 */
class WorkflowRepository(directory: File, private val globals: GlobalVariableRepository? = null) {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    // Lenient decoder used by [list]: decodes only the [WorkflowSummary] fields
    // and silently ignores the rest of the workflow JSON.
    private val summaryJson = Json { ignoreUnknownKeys = true }

    private val workflowsDir = File(directory, DIR_NAME).apply { mkdirs() }

    private val _changes = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /**
     * Emits once whenever the set of stored workflows, or any one of them, changed.
     *
     * The home-screen widgets are the reason this exists: nothing on a home screen
     * can poll, and the screens that *can* — the workflow list — already re-`list()`
     * on `ON_RESUME`, so before this there was no signal a background component
     * could subscribe to at all. It carries no payload on purpose; a widget reloads
     * the little it renders anyway, and a diff here would be a second model of the
     * store to keep true.
     *
     * `extraBufferCapacity = 1` with the default suspend-free `tryEmit` means a save
     * never waits on a slow subscriber, and a burst of saves collapses into one
     * redraw rather than queueing.
     */
    val changes: SharedFlow<Unit> = _changes.asSharedFlow()

    /**
     * Returns a lightweight summary of every persisted workflow, sorted by name
     * (case-insensitive). Files that fail to decode are skipped rather than
     * throwing — a corrupt file should not prevent the list from rendering.
     */
    suspend fun list(): List<WorkflowSummary> = withContext(Dispatchers.IO) {
        workflowsDir.listFiles { f -> f.isFile && f.name.endsWith(SUFFIX) }
            .orEmpty()
            .mapNotNull { file ->
                runCatching { summaryJson.decodeFromString(WorkflowSummary.serializer(), file.readText()) }
                    .getOrNull()
            }
            .sortedBy { it.name.lowercase() }
    }

    /**
     * Loads the workflow [id], or null when it does not exist, fails to decode,
     * or was written by an older schema version.
     */
    suspend fun load(id: String): Workflow? = withContext(Dispatchers.IO) {
        runCatching {
            val file = fileFor(id)
            if (!file.exists()) return@runCatching null
            json.decodeFromString(Workflow.serializer(), file.readText())
                .takeIf { it.schemaVersion >= Workflow.CURRENT_SCHEMA_VERSION }
                ?.copy(id = id)
                ?.let(::repaired)
        }.getOrNull()
    }

    /**
     * The graph with any legacy variable *names* turned into references to real
     * declarations, those declarations adopted into the global library, and any node
     * whose type this build no longer declares dropped ([pruneUnknownNodes]).
     *
     * The repaired graph is returned **in memory and not written back**. Writing on
     * load would turn `MacroEngineService.rearmAll` — which loads every enabled
     * workflow on boot — into a write storm, and would race the editor's debounced
     * save. The rewritten refs persist on the next ordinary save; until then this
     * reapplies on every load, which is free because it is idempotent.
     *
     * That is why the prune deletes nothing from disk, which is a second reason to
     * want it there: a node dropped because this build forgot its type comes *back*
     * if the build that declares it is installed again, right up until the next save.
     * It runs **after** [repairAiRefs] and not before, which is the one ordering
     * constraint among the three: `action.ai_agent` is a retired typeId that nothing
     * declares, so pruning first would delete the very nodes that rewrite exists to
     * carry forward.
     *
     * The adopted *declarations* are persisted immediately, because they are the
     * part that must survive: a ref pointing at a declaration nobody wrote down
     * reads as deleted.
     */
    private fun repaired(workflow: Workflow): Workflow {
        // Pure and lookup-free, so it runs whether or not a globals library is bound —
        // see `repairAiRefs` for why it needs nothing from the connection repository.
        val withAi = repairAiRefs(workflow)
        val pruned = pruneUnknownNodes(withAi).workflow
        val library = globals ?: return pruned
        val result = repairVariableRefs(pruned, library.list().associate { it.name to it.id })
        library.adopt(result.adopted)
        return result.workflow
    }

    /**
     * The single write path — [create], [rename], [setEnabled] and [updateMacro]
     * all land here, which is why [changes] is emitted from this one place rather
     * than from each of them. A failed write emits nothing: there is nothing new to
     * read.
     */
    suspend fun save(workflow: Workflow) {
        val written = withContext(Dispatchers.IO) {
            runCatching {
                fileFor(workflow.id).writeText(json.encodeToString(Workflow.serializer(), workflow))
            }.isSuccess
        }
        if (written) _changes.tryEmit(Unit)
    }

    suspend fun delete(id: String) {
        val deleted = withContext(Dispatchers.IO) {
            runCatching { fileFor(id).delete() }.getOrDefault(false)
        }
        if (deleted) _changes.tryEmit(Unit)
    }

    /**
     * Creates a brand-new empty workflow with a fresh id and the given [name],
     * persists it, and returns it.
     */
    suspend fun create(name: String): Workflow {
        val workflow = Workflow(id = UUID.randomUUID().toString(), name = name)
        save(workflow)
        return workflow
    }

    /**
     * Read-modify-write of the [Workflow.name] field only. Serialises against
     * itself via [flagMutex] so concurrent [setEnabled] toggles on the same
     * workflow do not clobber each other.
     */
    suspend fun rename(id: String, name: String) {
        flagMutex.lock()
        try {
            val current = load(id) ?: return
            save(current.copy(name = name, schemaVersion = Workflow.CURRENT_SCHEMA_VERSION))
        } finally {
            flagMutex.unlock()
        }
    }

    /**
     * Toggles the persisted [Workflow.enabled] flag for [id] without touching
     * the graph. Read-modifies-writes the single file, so it serialises against
     * itself (and [rename]) via [flagMutex]; concurrent graph [save]s from the
     * editor are rare and last-writer-wins is acceptable for this toggle.
     */
    suspend fun setEnabled(id: String, enabled: Boolean) {
        flagMutex.lock()
        try {
            val current = load(id) ?: return
            save(current.copy(enabled = enabled, schemaVersion = Workflow.CURRENT_SCHEMA_VERSION))
        } finally {
            flagMutex.unlock()
        }
    }

    /**
     * Everything the workflow list's Edit dialog can change, in one write.
     *
     * Not a [rename] followed by a separate appearance write: the dialog has one
     * Save button, and two writes can leave the file holding half of what it said —
     * a crash between them, or a [delete] slipping into the gap. Guarded by
     * [flagMutex] for the reason [rename] and [setEnabled] are, since it too
     * rewrites the whole file from a value it just read.
     *
     * Deliberately does **not** re-arm the macro. `Workflow.runtimeSignature`
     * excludes both appearance fields, so the editor's save gate already ignores a
     * recolour, and this path has no reason to be louder than that one.
     */
    suspend fun updateMacro(id: String, name: String, icon: MacroIcon, accent: MacroAccent) {
        flagMutex.lock()
        try {
            val current = load(id) ?: return
            save(
                current.copy(
                    name = name,
                    icon = icon,
                    accent = accent,
                    schemaVersion = Workflow.CURRENT_SCHEMA_VERSION,
                ),
            )
        } finally {
            flagMutex.unlock()
        }
    }

    private fun fileFor(id: String): File = File(workflowsDir, "$id$SUFFIX")

    private val flagMutex = Mutex()

    private companion object {
        const val DIR_NAME = "workflows"
        const val SUFFIX = ".json"
    }
}
