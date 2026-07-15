package com.example.ottomatic.data

import com.example.ottomatic.domain.model.Workflow
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Persists the current workflow as a JSON file in the app's files directory.
 */
class WorkflowRepository(private val directory: File) {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    private val file: File get() = File(directory, FILE_NAME)

    suspend fun load(): Workflow? = withContext(Dispatchers.IO) {
        runCatching {
            if (file.exists()) json.decodeFromString<Workflow>(file.readText()) else null
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
