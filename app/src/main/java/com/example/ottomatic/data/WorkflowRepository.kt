package com.example.ottomatic.data

import com.example.ottomatic.data.migration.WorkflowMigrator
import com.example.ottomatic.domain.model.Workflow
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Persists the current workflow as a JSON file in the app's files directory.
 *
 * On load, workflows written by older schema versions are migrated forward
 * by [WorkflowMigrator] before being returned. Saving always writes the
 * current schema version.
 */
class WorkflowRepository(private val directory: File) {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    private val file: File get() = File(directory, FILE_NAME)

    suspend fun load(): Workflow? = withContext(Dispatchers.IO) {
        runCatching {
            if (!file.exists()) return@runCatching null
            val raw = file.readText()
            WorkflowMigrator.migrate(raw)
        }.getOrNull()
    }

    suspend fun save(workflow: Workflow) {
        withContext(Dispatchers.IO) {
            runCatching {
                file.writeText(json.encodeToString(Workflow.serializer(), workflow))
            }
        }
    }

    private companion object {
        const val FILE_NAME = "workflow.json"
    }
}
