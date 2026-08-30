package io.github.m1n1m1.easymatic.data

import io.github.m1n1m1.easymatic.domain.model.GeofencePlace
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
 * Persists the geofence place library as a single JSON file at
 * `{filesDir}/places/geofences.json`.
 *
 * One file rather than one-per-place (as [WorkflowRepository] does): the list is
 * small, always read whole, and always rendered whole, so per-file storage would
 * only buy partial-failure isolation we do not need.
 *
 * The decoded list is held in [places] so callers get it synchronously. That
 * matters for the trigger side, where
 * [io.github.m1n1m1.easymatic.engine.trigger.TriggerHost.geofencePlace] resolves an
 * id while arming and has no suspending context to read from disk in. The cache
 * is the single source of truth after construction; every mutation updates it
 * and rewrites the file.
 *
 * A missing or corrupt file decodes to an empty library rather than throwing —
 * losing the places is bad, but crashing the app on startup is worse.
 */
class GeofencePlaceRepository(directory: File) {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    private val serializer = ListSerializer(GeofencePlace.serializer())

    private val file = File(directory, DIR_NAME).apply { mkdirs() }.let { File(it, FILE_NAME) }

    // Read synchronously at construction: ServiceLocator builds this during
    // Application.onCreate and the trigger host may resolve a place before any
    // coroutine has run. One small file read, comparable to opening prefs.
    private val cache = MutableStateFlow(readFile())

    /**
     * The library, sorted by name (case-insensitive), re-emitted on every
     * mutation so the picker and the node cards update without a reload.
     */
    val places: StateFlow<List<GeofencePlace>> = cache.asStateFlow()

    /** Snapshot of [places]. */
    fun list(): List<GeofencePlace> = cache.value

    /** The place with [id], or null when it was never created or has been deleted. */
    fun get(id: String): GeofencePlace? = cache.value.firstOrNull { it.id == id }

    /**
     * Inserts [place] or replaces the existing entry with the same id, then
     * persists. Returns the stored place.
     */
    suspend fun upsert(place: GeofencePlace): GeofencePlace {
        mutate { current ->
            val index = current.indexOfFirst { it.id == place.id }
            if (index >= 0) current.toMutableList().apply { this[index] = place } else current + place
        }
        return place
    }

    /** Removes the place with [id]; a no-op when it does not exist. */
    suspend fun delete(id: String) {
        mutate { current -> current.filterNot { it.id == id } }
    }

    /** Creates a place with a fresh id, persists it, and returns it. */
    suspend fun create(
        name: String,
        latitude: Double,
        longitude: Double,
        radiusMeters: Float = GeofencePlace.RELIABLE_MIN_RADIUS_METERS,
        address: String = "",
    ): GeofencePlace = upsert(
        GeofencePlace(
            id = UUID.randomUUID().toString(),
            name = name,
            latitude = latitude,
            longitude = longitude,
            radiusMeters = radiusMeters,
            address = address,
        ),
    )

    /**
     * Applies [transform] to the library and rewrites the file. Serialised by
     * [mutex] so two concurrent edits cannot each write their own view of the
     * list over the other's.
     */
    private suspend fun mutate(transform: (List<GeofencePlace>) -> List<GeofencePlace>) {
        mutex.withLock {
            val updated = transform(cache.value).sortedBy { it.name.lowercase() }
            cache.value = updated
            withContext(Dispatchers.IO) {
                runCatching { file.writeText(json.encodeToString(serializer, updated)) }
            }
        }
    }

    private fun readFile(): List<GeofencePlace> = runCatching {
        if (!file.exists()) return@runCatching emptyList()
        json.decodeFromString(serializer, file.readText()).sortedBy { it.name.lowercase() }
    }.getOrDefault(emptyList())

    private val mutex = Mutex()

    private companion object {
        const val DIR_NAME = "places"
        const val FILE_NAME = "geofences.json"
    }
}
