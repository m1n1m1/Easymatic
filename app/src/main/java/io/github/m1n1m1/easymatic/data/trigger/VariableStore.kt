package io.github.m1n1m1.easymatic.data.trigger

import io.github.m1n1m1.easymatic.core.model.NodeId
import io.github.m1n1m1.easymatic.core.service.VariableWrite
import io.github.m1n1m1.easymatic.core.service.Variables
import io.github.m1n1m1.easymatic.core.trigger.TriggerEvent
import io.github.m1n1m1.easymatic.core.trigger.TriggerSource
import io.github.m1n1m1.easymatic.domain.model.VariableRef
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Process-wide store of variable values, and the event source behind
 * [io.github.m1n1m1.easymatic.engine.trigger.VariableChangeTrigger].
 *
 * Keyed by [VariableRef.storeKey], so a workflow's own `count` and another's are
 * different entries. The store itself knows nothing about declarations, scopes or
 * constants — it is a keyed map with an event hub attached, and everything that
 * makes a variable a *declared* thing lives one layer up in
 * [io.github.m1n1m1.easymatic.engine.BoundVariables]. Keeping the split there is what
 * lets this stay as small as it is.
 *
 * An object rather than an injected class because it is an event hub as well as
 * a store: [AndroidTriggerHost] subscribes to it while arming, outside any
 * suspending context, exactly as it does for [io.github.m1n1m1.easymatic.core.trigger.TriggerBus].
 * Persistence is attached afterwards by `ServiceLocator` — see [attach].
 *
 * The map is concurrent because a variable is now written from actions running
 * on whatever dispatcher their macro is on, and read synchronously by
 * `value.variable` on the pull side of a different one.
 */
@Suppress("TooManyFunctions") // One member per way a value enters or leaves the store.
object VariableStore : Variables {

    private val values = ConcurrentHashMap<String, String>()

    private val _changes = MutableSharedFlow<TriggerEvent>(
        replay = 0,
        extraBufferCapacity = DEFAULT_BUFFER,
    )

    /** Flow of all variable-change events. Filter by `name` payload. */
    val changes: SharedFlow<TriggerEvent> = _changes.asSharedFlow()

    private var storage: Storage? = null

    /**
     * Loads persisted variables from `{directory}/variables.json` and writes
     * every later change back on [scope].
     *
     * Called once from `ServiceLocator.init`, before any macro can run. Reading
     * synchronously here is deliberate: a value node may be pulled the moment the
     * engine arms, and a counter that reads as unset for the first second would
     * be worse than the one small file read this costs — the same reasoning
     * [io.github.m1n1m1.easymatic.data.GeofencePlaceRepository] applies to its cache.
     */
    fun attach(directory: File, scope: CoroutineScope) {
        val file = File(directory, FILE_NAME)
        storage = Storage(file, scope)
        values.putAll(read(file))
    }

    /** Returns the current value for store key [name], or null if it has never been set. */
    override fun get(name: String): String? = values[name]

    /**
     * Sets store key [name] to [value], emitting a [TriggerEvent] and persisting
     * when this actually changes something.
     *
     * The no-op on an unchanged value is load-bearing rather than an
     * optimisation: a macro that re-writes the same variable every minute must
     * not fire a `trigger.variable_change` every minute, or "when the state
     * changes" would mean "whenever anyone looked".
     *
     * **The store never refuses.** It is a keyed map with an event hub attached and
     * knows nothing about declarations, so a constant is turned away one layer up,
     * by the [io.github.m1n1m1.easymatic.engine.BoundVariables] that does know. Keeping
     * this half scope-blind is what lets it stay this small.
     */
    override fun set(name: String, value: String): VariableWrite {
        if (values.put(name, value) == value) return VariableWrite.STORED
        _changes.tryEmit(
            TriggerEvent(
                source = TriggerSource.VARIABLE,
                triggerNodeId = NodeId.BROADCAST,
                payload = mapOf(
                    KEY_NAME to name,
                    KEY_VALUE to value,
                    "timestamp" to System.currentTimeMillis().toString(),
                ),
            ),
        )
        storage?.save { values.toMap() }
        return VariableWrite.STORED
    }

    /** Stream of change events filtered to store key [name] (used by the host). */
    fun changesFor(name: String): Flow<TriggerEvent> =
        _changes.asSharedFlow().filter { it.payload[KEY_NAME] == name }

    /** Every key and value right now — the read the editor's variables panel shows. */
    fun snapshot(): Map<String, String> = values.toMap()

    /**
     * Drops every value belonging to [workflowId].
     *
     * Called when a workflow is deleted, beside the run log's own cleanup: a macro's
     * console goes with it, and so should what it remembered. Without this the file
     * accumulates the values of macros that no longer exist, keyed by an id nothing
     * will ever ask for again.
     */
    fun clearScope(workflowId: String) {
        val prefix = VariableRef.scopePrefix(workflowId)
        val removed = values.keys.filter { it.startsWith(prefix) }
        if (removed.isEmpty()) return
        removed.forEach { values.remove(it) }
        storage?.save { values.toMap() }
    }

    /**
     * Moves the value stored under the pre-scoping bare [name] to [key].
     *
     * The one migration this file needs. Before scoping, a variable *was* its name
     * and every key was bare; a legacy macro's counter would otherwise still be on
     * disk under a key nothing looks up any more, and the user would find a fresh
     * zero where a count used to be. Called exactly once per adopted name, when the
     * global declaration that replaces it is minted.
     *
     * Silent when there is nothing there — most names are adopted from a graph that
     * has never run.
     */
    fun adoptLegacy(name: String, key: String) {
        val legacy = values.remove(name) ?: return
        values.putIfAbsent(key, legacy)
        storage?.save { values.toMap() }
    }

    /**
     * Replaces every value at once, after a restore rewrote the libraries from outside.
     *
     * **Emits no change event**, deliberately, and this is the line someone will later
     * want to "fix": a restore is not a macro writing a variable, and firing
     * `trigger.variable_change` on every armed macro mid-restore would run them against
     * half-replaced libraries. The re-arm that follows a restore re-reads everything.
     */
    fun replaceAll(replacement: Map<String, String>) {
        values.clear()
        values.putAll(replacement)
        storage?.save { values.toMap() }
    }

    /** Drops the value stored under [key]; used when its declaration is deleted. */
    fun remove(key: String) {
        if (values.remove(key) == null) return
        storage?.save { values.toMap() }
    }

    /** Drops every variable and its file. Test seam; nothing in the app calls it. */
    internal fun clear() {
        values.clear()
        storage = null
    }

    /**
     * A missing or corrupt file reads as no variables rather than throwing —
     * losing a counter is bad, crashing on startup is worse.
     */
    private fun read(file: File): Map<String, String> = runCatching {
        if (!file.exists()) return@runCatching emptyMap()
        JSON.decodeFromString(SERIALIZER, file.readText())
    }.getOrDefault(emptyMap())

    /**
     * The file half, kept separate so the store works unattached (unit tests,
     * previews) with variables that simply do not outlive the process.
     *
     * Every write serialises the whole map, so a burst of writes coalesces rather
     * than accumulating a queue of diffs.
     *
     * The snapshot is taken **inside the lock**, not handed in. Nothing orders the
     * launched coroutines against each other, so a caller that snapshotted first
     * can acquire the mutex last — and three quick writes to the same variable
     * could leave the *first* value on disk. Reading the live map under the lock
     * makes whichever write runs last correct by construction, whatever order they
     * arrive in.
     */
    private class Storage(private val file: File, private val scope: CoroutineScope) {
        private val mutex = Mutex()

        fun save(snapshot: () -> Map<String, String>) {
            scope.launch {
                mutex.withLock {
                    val current = snapshot()
                    withContext(Dispatchers.IO) {
                        runCatching { file.writeText(JSON.encodeToString(SERIALIZER, current)) }
                    }
                }
            }
        }
    }

    private const val KEY_NAME = "name"
    private const val KEY_VALUE = "value"
    private const val FILE_NAME = "variables.json"
    private const val DEFAULT_BUFFER = 64

    private val JSON = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val SERIALIZER = MapSerializer(String.serializer(), String.serializer())
}
