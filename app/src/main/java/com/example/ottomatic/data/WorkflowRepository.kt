package com.example.ottomatic.data

import com.example.ottomatic.domain.model.Workflow
import com.example.ottomatic.domain.model.WorkflowSummary
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
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
 */
class WorkflowRepository(directory: File) {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    // Lenient decoder used by [list]: decodes only the [WorkflowSummary] fields
    // and silently ignores the rest of the workflow JSON.
    private val summaryJson = Json { ignoreUnknownKeys = true }

    private val workflowsDir = File(directory, DIR_NAME).apply { mkdirs() }

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
        }.getOrNull()
    }

    suspend fun save(workflow: Workflow) {
        withContext(Dispatchers.IO) {
            runCatching {
                fileFor(workflow.id).writeText(json.encodeToString(Workflow.serializer(), workflow))
            }
        }
    }

    suspend fun delete(id: String) {
        withContext(Dispatchers.IO) {
            runCatching { fileFor(id).delete() }
        }
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

    private fun fileFor(id: String): File = File(workflowsDir, "$id$SUFFIX")

    private val flagMutex = Mutex()

    private companion object {
        const val DIR_NAME = "workflows"
        const val SUFFIX = ".json"
    }
}
