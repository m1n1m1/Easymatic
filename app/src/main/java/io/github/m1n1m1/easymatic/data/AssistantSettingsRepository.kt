package io.github.m1n1m1.easymatic.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
private data class AssistantSettings(val modelRef: String = "")

/**
 * Which model the graph assistant asks, remembered between sessions.
 *
 * One field, and deliberately not a node's config: this is a *preference about the
 * editor* rather than anything a macro carries, so it must not travel with an exported
 * workflow and must not turn a saved graph into a broken reference when the profile it
 * names is deleted. A blank simply means nothing has been chosen yet, and the composer
 * says so.
 *
 * Its own file rather than a field on `AiConnectionRepository`, on the argument that
 * library holds: a connection library is the user's credentials and the ways of asking
 * through them, and "which of those the editor used last" is not one of those things.
 * Deleting the connection does not need to reach in here — the picker resolves the id
 * on every open, and one that names nothing renders as nothing chosen.
 */
class AssistantSettingsRepository(filesDir: File) : ReloadableLibrary {

    private val file = File(File(filesDir, DIRECTORY).apply { mkdirs() }, FILE_NAME)
    private val json = Json { ignoreUnknownKeys = true }

    private val state = MutableStateFlow(read().modelRef)

    /** The last model profile the assistant was asked through, or blank. */
    val modelRef: StateFlow<String> = state

    fun choose(modelRef: String) {
        state.value = modelRef
        runCatching { file.writeText(json.encodeToString(AssistantSettings(modelRef))) }
    }

    /** Re-reads the file after a restore replaced it — see [ReloadableLibrary]. */
    override suspend fun reload() {
        state.value = read().modelRef
    }

    /**
     * Read synchronously at construction, and **never throws**.
     *
     * A preference that cannot be read is a preference that was not set, which the
     * composer already handles; taking the process down over it would be the wrong
     * trade for one string.
     */
    private fun read(): AssistantSettings = runCatching {
        json.decodeFromString<AssistantSettings>(file.readText())
    }.getOrDefault(AssistantSettings())

    private companion object {
        const val DIRECTORY = "ai"
        const val FILE_NAME = "assistant.json"
    }
}
