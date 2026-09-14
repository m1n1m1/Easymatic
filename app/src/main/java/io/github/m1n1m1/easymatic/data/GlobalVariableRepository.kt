package io.github.m1n1m1.easymatic.data

import io.github.m1n1m1.easymatic.data.trigger.VariableStore
import io.github.m1n1m1.easymatic.domain.model.VariableDeclaration
import io.github.m1n1m1.easymatic.domain.model.VariableRef
import io.github.m1n1m1.easymatic.domain.registry.GlobalVariables
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
 * Persists the global variable declarations as a single JSON file at
 * `{filesDir}/variables/globals.json`.
 *
 * Shaped after [GeofencePlaceRepository], for the same reasons: the list is small,
 * always read whole and always rendered whole, and it has to be readable
 * *synchronously* because `ServiceLocator` publishes it to
 * [io.github.m1n1m1.easymatic.domain.registry.GlobalVariables] before anything can arm
 * and a `value.variable` may be pulled the moment one does.
 *
 * Only the declarations live here. The *values* are in
 * [io.github.m1n1m1.easymatic.data.trigger.VariableStore] alongside every workflow's
 * own, keyed by [VariableRef.storeKey].
 *
 * A missing or corrupt file decodes to an empty library rather than throwing —
 * losing the declarations is bad, crashing the app on startup is worse.
 */
class GlobalVariableRepository(directory: File) : ReloadableLibrary {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    private val serializer = ListSerializer(VariableDeclaration.serializer())

    private val file = File(directory, DIR_NAME).apply { mkdirs() }.let { File(it, FILE_NAME) }

    private val cache = MutableStateFlow(readFile())

    /** The library, sorted by name, re-emitted on every mutation. */
    val variables: StateFlow<List<VariableDeclaration>> = cache.asStateFlow()

    /** Snapshot of [variables]. */
    fun list(): List<VariableDeclaration> = cache.value

    /** The declaration with [id], or null when it was never created or has been deleted. */
    fun get(id: String): VariableDeclaration? = cache.value.firstOrNull { it.id == id }

    /** Inserts [declaration] or replaces the entry with the same id, then persists. */
    suspend fun upsert(declaration: VariableDeclaration) {
        mutate { current ->
            val index = current.indexOfFirst { it.id == declaration.id }
            if (index >= 0) {
                current.toMutableList().apply { this[index] = declaration }
            } else {
                current + declaration
            }
        }
    }

    /** Removes the declaration with [id]; a no-op when it does not exist. */
    suspend fun delete(id: String) {
        mutate { current -> current.filterNot { it.id == id } }
    }

    /**
     * Adds the declarations a legacy workflow's bare variable names were turned
     * into, and brings each one's stored value with it.
     *
     * Called from [WorkflowRepository.load] via the repair. Before variables were
     * declared, a variable *was* its name and the store was keyed by that name, so
     * a counter that has been counting for weeks is sitting on disk under a key
     * nothing looks up any more — moving it is what stops the user finding a fresh
     * zero where their count used to be.
     *
     * Blocking, and deliberately so: it runs inside a synchronous `load`, the file
     * is tiny, and an adopted declaration that had not landed before the graph is
     * armed would resolve as deleted.
     */
    fun adopt(declarations: List<VariableDeclaration>) {
        if (declarations.isEmpty()) return
        val byName = cache.value.associateBy { it.name }
        val fresh = declarations.filterNot { it.name in byName }
        if (fresh.isEmpty()) return
        fresh.forEach { VariableStore.adoptLegacy(it.name, VariableRef.storeKey(VariableRef.Global(it.id), "")) }
        cache.value = (cache.value + fresh).sortedBy { it.name.lowercase() }
        writeFile(cache.value)
    }

    /**
     * Re-reads the file after a restore replaced it — see [ReloadableLibrary] — and
     * republishes [GlobalVariables], which `ServiceLocator` hydrates once at start-up
     * and nothing else keeps in step: a restored declaration that never reached the
     * registry would resolve as deleted in every config form.
     */
    override suspend fun reload() {
        mutex.withLock {
            cache.value = withContext(Dispatchers.IO) { readFile() }
            GlobalVariables.hydrate(cache.value)
        }
    }

    private suspend fun mutate(transform: (List<VariableDeclaration>) -> List<VariableDeclaration>) {
        mutex.withLock {
            val updated = transform(cache.value).sortedBy { it.name.lowercase() }
            cache.value = updated
            withContext(Dispatchers.IO) { writeFile(updated) }
        }
    }

    private fun writeFile(declarations: List<VariableDeclaration>) {
        runCatching { file.writeText(json.encodeToString(serializer, declarations)) }
    }

    private fun readFile(): List<VariableDeclaration> = runCatching {
        if (!file.exists()) return@runCatching emptyList()
        json.decodeFromString(serializer, file.readText()).sortedBy { it.name.lowercase() }
    }.getOrDefault(emptyList())

    private val mutex = Mutex()

    private companion object {
        const val DIR_NAME = "variables"
        const val FILE_NAME = "globals.json"
    }
}
