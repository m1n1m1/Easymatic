package com.example.ottomatic.data

import com.example.ottomatic.data.security.Secrets
import com.example.ottomatic.domain.model.HaEntity
import com.example.ottomatic.domain.model.HaService
import com.example.ottomatic.domain.model.HubAuthMode
import com.example.ottomatic.domain.model.SmartHomeHub
import com.example.ottomatic.domain.model.SmartHomeResource
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Persists the smart-home hub library as a single JSON file at
 * `{filesDir}/smarthome/hubs.json`.
 *
 * Shaped after [MailAccountRepository], for its reasons: the list is small, always
 * read whole and always rendered whole, and it has to be readable **synchronously**,
 * because a picker's first frame draws the cached resource snapshot and has no
 * suspending context to read a file in.
 *
 * The rule about the credential is [MailAccountRepository]'s, word for word:
 * **this repository never hands the plaintext application key to `feature/`**.
 * [applicationKey] exists for the transport; the pairing flow writes with [setKeys]
 * and never reads back. Unlike a password there is nothing to re-type either — an
 * application key is minted by the bridge and can only be replaced by pairing
 * again, which is exactly what [needsPairing] reports.
 *
 * A missing or corrupt file decodes to an empty library rather than throwing, for
 * [GeofencePlaceRepository]'s reason: losing the hubs is bad, crashing the app on
 * startup is worse.
 */
@Suppress("TooManyFunctions") // One member per thing the library stores or updates; the model sets the count.
class SmartHomeHubRepository(
    directory: File,
    private val secrets: Secrets,
) {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    private val serializer = ListSerializer(SmartHomeHub.serializer())

    private val file = File(directory, DIR_NAME).apply { mkdirs() }.let { File(it, FILE_NAME) }

    // Read synchronously at construction, as the other libraries are: a picker may
    // draw a hub's resources before any coroutine has run.
    private val cache = MutableStateFlow(readFile())

    /** The library, sorted by name, re-emitted on every mutation. */
    val hubs: StateFlow<List<SmartHomeHub>> = cache.asStateFlow()

    /** Snapshot of [hubs]. */
    fun list(): List<SmartHomeHub> = cache.value

    /** The hub with [id], or null when it was never created or has been deleted. */
    fun get(id: String): SmartHomeHub? = cache.value.firstOrNull { it.id == id }

    /**
     * The application key for [id] right now, or null — no such hub, no stored
     * secret, or a key this device has lost.
     *
     * Three failures, one answer, because nothing a caller could do differs between
     * them: every one of them means "this bridge has to be paired again".
     */
    fun applicationKey(id: String): String? =
        get(id)?.secret?.takeIf { it.isNotBlank() }?.let(secrets::open)

    /**
     * Whether [id] would fail to authenticate for want of a readable key.
     *
     * **Derived on every call, never stored**, on [MailAccountRepository.needsPassword]'s
     * reasoning — and the restore case it exists for is the same one, only worse:
     * an AndroidKeyStore key is never backed up, so a cloud restore brings the hub
     * across and leaves the key behind. For mail that costs one typed field; here
     * it costs a walk to the bridge and a press of the link button, which is a
     * thing worth saying on the row rather than discovering when a macro runs.
     */
    fun needsPairing(id: String): Boolean =
        get(id)?.let { it.secret.isBlank() || secrets.open(it.secret) == null } ?: false

    /** Inserts [hub] or replaces the entry with the same id, then persists. */
    suspend fun upsert(hub: SmartHomeHub): SmartHomeHub {
        mutate { current ->
            val index = current.indexOfFirst { it.id == hub.id }
            if (index >= 0) current.toMutableList().apply { this[index] = hub } else current + hub
        }
        return hub
    }

    /** Removes the hub with [id]; a no-op when it does not exist. */
    suspend fun delete(id: String) {
        mutate { current -> current.filterNot { it.id == id } }
    }

    /** Creates a hub with a fresh id, persists it, and returns it. */
    suspend fun create(hub: SmartHomeHub): SmartHomeHub =
        upsert(hub.copy(id = UUID.randomUUID().toString()))

    /**
     * Seals the keys the bridge issued at pairing onto [id]. Returns false when
     * there is no such hub or this device would not seal them — in which case
     * nothing is written, so a keystore failure leaves a working hub working rather
     * than replacing its key with something unreadable.
     *
     * [streamKey] is blank for a bridge that issued none; it is stored unread
     * because the bridge hands it out exactly once.
     */
    suspend fun setKeys(id: String, applicationKey: String, streamKey: String = ""): Boolean {
        val hub = get(id)
        val sealedKey = hub?.let { secrets.seal(applicationKey) }
        if (hub == null || sealedKey == null) return false
        val sealedStream = streamKey.takeIf { it.isNotBlank() }?.let(secrets::seal).orEmpty()
        upsert(hub.copy(secret = sealedKey, streamSecret = sealedStream))
        return true
    }

    /**
     * Pins [sha256] as the certificate this hub is expected to present.
     *
     * Separate from [setKeys] because the two move independently: a bridge whose
     * firmware rotated its certificate keeps its application key, and re-trusting it
     * must not cost a re-pair.
     */
    suspend fun setCertificate(id: String, sha256: String) {
        get(id)?.let { upsert(it.copy(certSha256 = sha256)) }
    }

    /** Replaces the cached snapshot the pickers draw from. Leaves the keys and the pin alone. */
    suspend fun setResources(id: String, resources: List<SmartHomeResource>, refreshedAtEpochMs: Long) {
        get(id)?.let {
            upsert(it.copy(resources = resources, resourcesRefreshedAtEpochMs = refreshedAtEpochMs))
        }
    }

    /**
     * The access token for [id] right now, or null on any of [applicationKey]'s three
     * failures.
     *
     * A **second name for the same field**, and deliberately not a second field: what
     * a Hue bridge calls an application key and what Home Assistant calls an access
     * token are the same thing in every way that matters here — the one credential
     * sent with every request, sealed under one alias, lost together on a restore. Two
     * fields would mean [needsPairing] and [isComplete] each growing a branch to ask
     * which one to look at, over a distinction with no consequence. Two *names* cost
     * nothing and let each caller say what it means.
     */
    fun accessToken(id: String): String? = applicationKey(id)

    /** The sealed OAuth refresh token for [id], or null when it has none. */
    fun refreshToken(id: String): String? =
        get(id)?.refreshSecret?.takeIf { it.isNotBlank() }?.let(secrets::open)

    /**
     * Seals a Home Assistant credential onto [id]. Returns false when there is no such
     * hub or this device would not seal it — in which case **nothing is written**, on
     * [setKeys]' rule and for its reason: a keystore that refuses must leave a working
     * hub working rather than replacing its token with something unreadable.
     *
     * [refreshToken] is blank on the pasted-token path, where there is nothing to
     * renew from and nothing ever expires. It is **only overwritten when non-blank**,
     * because a refresh exchange returns a new access token and, on most servers, no
     * new refresh token — writing the blank through would sign the user out at the
     * next expiry, hours later, with nothing connecting the two events.
     */
    @Suppress("ReturnCount") // Two sealing failures that must write nothing, then success.
    suspend fun setToken(
        id: String,
        accessToken: String,
        refreshToken: String = "",
        expiresAtEpochMs: Long = 0,
        mode: HubAuthMode = HubAuthMode.TOKEN,
    ): Boolean {
        val hub = get(id)
        val sealedToken = hub?.let { secrets.seal(accessToken) }
        if (hub == null || sealedToken == null) return false
        val sealedRefresh = refreshToken.takeIf { it.isNotBlank() }?.let(secrets::seal)
        if (refreshToken.isNotBlank() && sealedRefresh == null) return false
        upsert(
            hub.copy(
                secret = sealedToken,
                refreshSecret = sealedRefresh ?: hub.refreshSecret,
                tokenExpiresAtEpochMs = expiresAtEpochMs,
                authMode = mode,
            ),
        )
        return true
    }

    /**
     * Replaces everything a Home Assistant refresh reads: the light-shaped resources
     * the two light pickers draw from, and the entity and service lists the two Home
     * Assistant pickers draw from.
     *
     * One call rather than three because they are one snapshot taken at one moment,
     * and writing them separately would let a picker open between two of them and
     * render an entity list that disagrees with the light list beside it.
     */
    suspend fun setHomeAssistantSnapshot(
        id: String,
        resources: List<SmartHomeResource>,
        entities: List<HaEntity>,
        services: List<HaService>,
        refreshedAtEpochMs: Long,
    ) {
        get(id)?.let {
            upsert(
                it.copy(
                    resources = resources,
                    entities = entities,
                    services = services,
                    resourcesRefreshedAtEpochMs = refreshedAtEpochMs,
                ),
            )
        }
    }

    private suspend fun mutate(transform: (List<SmartHomeHub>) -> List<SmartHomeHub>) {
        mutex.withLock {
            val updated = transform(cache.value).sortedBy { it.name.lowercase() }
            cache.value = updated
            withContext(Dispatchers.IO) {
                runCatching { file.writeText(json.encodeToString(serializer, updated)) }
            }
        }
    }

    private fun readFile(): List<SmartHomeHub> = runCatching {
        if (!file.exists()) return@runCatching emptyList()
        json.decodeFromString(serializer, file.readText()).sortedBy { it.name.lowercase() }
    }.getOrDefault(emptyList())

    private val mutex = Mutex()

    private companion object {
        const val DIR_NAME = "smarthome"
        const val FILE_NAME = "hubs.json"
    }
}
