package com.example.ottomatic.data

import com.example.ottomatic.core.service.AiModel
import com.example.ottomatic.data.security.Secrets
import com.example.ottomatic.domain.model.AiConnection
import com.example.ottomatic.domain.model.AiModelProfile
import com.example.ottomatic.domain.model.AiProvider
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Persists the AI connection library as a single JSON file at
 * `{filesDir}/ai/connections.json`.
 *
 * Shaped after [MailAccountRepository] and [SmartHomeHubRepository], for their
 * reasons: the list is small, always read whole and always rendered whole, and it
 * has to be readable **synchronously**, because a picker's first frame draws it and
 * the engine may reach an AI node before any coroutine has run.
 *
 * The rule about the credential is theirs, word for word: **this repository never
 * hands the plaintext key to `feature/`**. [apiKey] exists for the transport; the
 * editor writes with [setKey] and never reads back. A key on screen is a key in a
 * screenshot, a recents thumbnail and an accessibility tree, and unlike a mail
 * password there is nothing to re-read it *for* — replacing it costs one paste.
 *
 * A missing or corrupt file decodes to an empty library rather than throwing, for
 * [GeofencePlaceRepository]'s reason: needing to paste a key again is bad, crashing
 * the app on startup is worse.
 */
@Suppress("TooManyFunctions") // One member per thing the library stores or updates; the model sets the count.
class AiConnectionRepository(
    directory: File,
    private val secrets: Secrets,
) {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    private val serializer = ListSerializer(AiConnection.serializer())

    private val directory = File(directory, DIR_NAME).apply { mkdirs() }

    private val file = File(this.directory, FILE_NAME)

    /** Written once the profile upconvert has run; see [upconvertProfiles]. */
    private val marker = File(this.directory, PROFILES_MARKER_NAME)

    // Read synchronously at construction, as the other libraries are.
    private val cache = MutableStateFlow(readFile())

    /** The library, sorted by name, re-emitted on every mutation. */
    val connections: StateFlow<List<AiConnection>> = cache.asStateFlow()

    /** Snapshot of [connections]. */
    fun list(): List<AiConnection> = cache.value

    /** The connection with [id], or null when it was never created or has been deleted. */
    fun get(id: String): AiConnection? = cache.value.firstOrNull { it.id == id }

    /**
     * The connection holding the profile with [profileId], and that profile.
     *
     * A node stores the **profile** id and nothing else, so every read starts here.
     * Searching rather than indexing is deliberate: the library is a handful of
     * connections with a handful of profiles each, and a second map is a second thing
     * to keep in step with the first.
     */
    fun resolve(profileId: String): Pair<AiConnection, AiModelProfile>? = cache.value
        .firstNotNullOfOrNull { connection ->
            connection.models.firstOrNull { it.id == profileId }?.let { connection to it }
        }

    /** Just the account half of [resolve], for a picker rendering "Household · Personal key". */
    fun connectionForProfile(profileId: String): AiConnection? = resolve(profileId)?.first

    /**
     * The API key for [id] right now, or null — no such connection, no stored
     * secret, or a key this device has lost.
     *
     * Three failures, one answer, because nothing a caller could do differs between
     * them: every one of them means "this connection has to be set up again".
     */
    fun apiKey(id: String): String? =
        get(id)?.secret?.takeIf { it.isNotBlank() }?.let(secrets::open)

    /**
     * Whether [id] would fail to authenticate for want of a readable key.
     *
     * **Derived on every call, never stored**, on [MailAccountRepository.needsPassword]'s
     * and [SmartHomeHubRepository.needsPairing]'s reasoning, and for the same restore
     * case: an AndroidKeyStore key is never backed up, so a cloud restore brings the
     * library across and leaves the key that sealed it behind. A persisted flag would
     * then say "set up" about a credential nothing can read.
     */
    fun needsKey(id: String): Boolean =
        get(id)?.let { it.secret.isBlank() || secrets.open(it.secret) == null } ?: false

    /** Inserts [connection] or replaces the entry with the same id, then persists. */
    suspend fun upsert(connection: AiConnection): AiConnection {
        mutate { current ->
            val index = current.indexOfFirst { it.id == connection.id }
            if (index >= 0) {
                current.toMutableList().apply { this[index] = connection }
            } else {
                current + connection
            }
        }
        return connection
    }

    /** Creates a connection with a fresh id, persists it, and returns it. */
    suspend fun create(name: String, provider: AiProvider): AiConnection =
        upsert(AiConnection(id = UUID.randomUUID().toString(), name = name, provider = provider))

    /** Removes the connection with [id]; a no-op when it does not exist. */
    suspend fun delete(id: String) {
        mutate { current -> current.filterNot { it.id == id } }
    }

    /**
     * Seals [plaintext] onto [id]. Returns false when there is no such connection or
     * this device would not seal it — in which case **nothing is written**, so a
     * keystore hiccup leaves a working connection working rather than replacing its
     * key with something unreadable. [SmartHomeHubRepository.setKeys]' rule.
     *
     * Surrounding whitespace is trimmed rather than preserved: a key arrives here by
     * paste, and a trailing newline picked up from a web page would be sent verbatim
     * in a header and refused as invalid, which names nothing about what actually
     * went wrong.
     */
    suspend fun setKey(id: String, plaintext: String): Boolean {
        val connection = get(id)
        val sealed = connection?.let { secrets.seal(plaintext.trim()) }
        if (connection == null || sealed == null) return false
        upsert(connection.copy(secret = sealed))
        return true
    }

    private suspend fun mutate(transform: (List<AiConnection>) -> List<AiConnection>) {
        mutex.withLock {
            val updated = transform(cache.value).sortedBy { it.name.lowercase() }
            cache.value = updated
            withContext(Dispatchers.IO) { writeFile(updated) }
        }
    }

    private fun writeFile(connections: List<AiConnection>) {
        runCatching { file.writeText(json.encodeToString(serializer, connections)) }
    }

    private fun readFile(): List<AiConnection> = runCatching {
        if (!file.exists()) return@runCatching adoptSingleKey()
        val stored = json.decodeFromString(serializer, file.readText()).sortedBy { it.name.lowercase() }
        upconvertProfiles(stored)
    }.getOrDefault(emptyList())

    /**
     * Splits a pre-profile connection into the three [AiModelProfile]s it was already
     * describing.
     *
     * The old layout held one standing prompt and three model-id overrides on the
     * connection itself, which is a provider account doing a model preset's job.
     * Every such connection becomes three profiles — Fast, Balanced and Thorough —
     * each carrying its own legacy id and a copy of the prompt, so nothing a user
     * configured is lost and no node has to be re-pointed by hand.
     *
     * **Deterministic ids**, from [AiModelProfile.legacyId]: a node saved before this
     * holds a connection id and a tier, and deriving the profile id from exactly those
     * two is what lets `repairAiRefs` repair a node as a pure function of its own
     * config. The two migrations therefore need no ordering between them, which
     * matters because a workflow is loaded on a different thread from this file.
     *
     * A connection with **no** legacy ids still gets three profiles, with blank
     * [AiModelProfile.modelId]s: blank already means "the provider's own id for this
     * tier", so that is the same three choices the user had before, named.
     *
     * **A marker file and not "has no profiles" decides whether this has run**, and
     * the difference is a real bug rather than a nicety. An empty list cannot tell a
     * pre-profile connection from one whose profiles the user *deleted* — so keying on
     * it would put three profiles back every time the app started, on a connection
     * somebody had deliberately emptied. The marker is [adoptSingleKey]'s
     * one-way-and-self-erasing idiom in its other form: there the old file is deleted,
     * here a new one is written, and both mean "this conversion has happened".
     *
     * Safe to remove entirely, marker and all, once no install predates profiles.
     */
    private fun upconvertProfiles(stored: List<AiConnection>): List<AiConnection> {
        if (marker.exists()) return stored
        val upconverted = stored.map { connection ->
            if (connection.models.isNotEmpty()) connection else connection.copy(
                models = AiModel.entries.map { effort ->
                    AiModelProfile(
                        id = AiModelProfile.legacyId(connection.id, effort),
                        name = effort.name.lowercase().replaceFirstChar { it.uppercase() },
                        modelId = connection.legacyModelId(effort),
                        effort = effort,
                        systemPrompt = connection.systemPrompt,
                    )
                },
                systemPrompt = "",
                fastModel = "",
                balancedModel = "",
                thoroughModel = "",
            )
        }
        if (upconverted != stored) writeFile(upconverted)
        runCatching { marker.createNewFile() }
        return upconverted
    }

    private fun AiConnection.legacyModelId(effort: AiModel): String = when (effort) {
        AiModel.FAST -> fastModel
        AiModel.BALANCED -> balancedModel
        AiModel.THOROUGH -> thoroughModel
    }

    /**
     * Brings across the key stored by the single-key layout this library replaced.
     *
     * The old file held one sealed key and no id, because the first version argued
     * that one key per phone was the whole model. Re-sealing is not needed — the
     * ciphertext is under the same alias — so this is a rename with a generated id
     * wrapped around it, and the user is not silently asked to go and find their key
     * again for a change they did not make.
     *
     * Deliberately **one-way and self-deleting**: it runs only when there is no
     * library file at all, and removes the old one once the new one is written, so
     * it cannot resurrect a connection somebody has since deleted. Safe to remove
     * entirely once no install predates the library.
     */
    private fun adoptSingleKey(): List<AiConnection> {
        val legacy = File(directory, LEGACY_FILE_NAME)
        if (!legacy.exists()) return emptyList()
        val sealed = runCatching {
            json.decodeFromString(LegacyKey.serializer(), legacy.readText()).secret
        }.getOrNull().orEmpty()
        val adopted = if (sealed.isBlank()) {
            emptyList()
        } else {
            listOf(
                AiConnection(
                    id = UUID.randomUUID().toString(),
                    name = LEGACY_NAME,
                    provider = AiProvider.GEMINI,
                    secret = sealed,
                ),
            )
        }
        writeFile(adopted)
        runCatching { legacy.delete() }
        return adopted
    }

    /** The single-key layout this library replaced. Read once, by [adoptSingleKey]. */
    @Serializable
    private data class LegacyKey(val secret: String = "")

    private val mutex = Mutex()

    private companion object {
        const val DIR_NAME = "ai"
        const val FILE_NAME = "connections.json"
        const val LEGACY_FILE_NAME = "key.json"
        const val PROFILES_MARKER_NAME = ".profiles"
        const val LEGACY_NAME = "Gemini"
    }
}
