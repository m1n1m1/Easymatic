package io.github.m1n1m1.easymatic.data

import io.github.m1n1m1.easymatic.domain.model.NfcTag
import java.io.File
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
 * Persists the NFC tag library as a single JSON file at `{filesDir}/nfc/tags.json`.
 *
 * Shaped after [GeofencePlaceRepository], for the same reasons: the list is small,
 * always read whole and always rendered whole, and it has to be readable
 * **synchronously**, because `TriggerHost.nfcTag` resolves a name while arming and
 * has no suspending context to read a file in.
 *
 * The one structural difference is that [NfcTag.uid] is the key, so there is no
 * `create` taking a name and minting an id — [upsert] is the whole write API, and
 * scanning a tag that is already saved updates it in place.
 */
class NfcTagRepository(directory: File) {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    private val serializer = ListSerializer(NfcTag.serializer())

    private val file = File(directory, DIR_NAME).apply { mkdirs() }.let { File(it, FILE_NAME) }

    private val cache = MutableStateFlow(readFile())

    /** The library, sorted by name, re-emitted on every mutation. */
    val tags: StateFlow<List<NfcTag>> = cache.asStateFlow()

    /** Snapshot of [tags]. */
    fun list(): List<NfcTag> = cache.value

    /** The tag with this hardware id, or null when it was never saved or has been deleted. */
    fun get(uid: String): NfcTag? = cache.value.firstOrNull { it.uid == uid }

    /** Inserts [tag] or replaces the entry with the same uid, then persists. */
    suspend fun upsert(tag: NfcTag): NfcTag {
        mutate { current ->
            val index = current.indexOfFirst { it.uid == tag.uid }
            if (index >= 0) current.toMutableList().apply { this[index] = tag } else current + tag
        }
        return tag
    }

    /** Removes the tag with [uid]; a no-op when it does not exist. */
    suspend fun delete(uid: String) {
        mutate { current -> current.filterNot { it.uid == uid } }
    }

    private suspend fun mutate(transform: (List<NfcTag>) -> List<NfcTag>) {
        mutex.withLock {
            val updated = transform(cache.value).sortedBy { it.name.lowercase() }
            cache.value = updated
            withContext(Dispatchers.IO) {
                runCatching { file.writeText(json.encodeToString(serializer, updated)) }
            }
        }
    }

    private fun readFile(): List<NfcTag> = runCatching {
        if (!file.exists()) return@runCatching emptyList()
        json.decodeFromString(serializer, file.readText()).sortedBy { it.name.lowercase() }
    }.getOrDefault(emptyList())

    private val mutex = Mutex()

    private companion object {
        const val DIR_NAME = "nfc"
        const val FILE_NAME = "tags.json"
    }
}
