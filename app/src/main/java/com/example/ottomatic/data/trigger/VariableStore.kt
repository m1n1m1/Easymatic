package com.example.ottomatic.data.trigger

import com.example.ottomatic.core.model.NodeId
import com.example.ottomatic.core.service.Variables
import com.example.ottomatic.core.trigger.TriggerEvent
import com.example.ottomatic.core.trigger.TriggerSource
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
 * Process-wide store for named string variables, and the event source behind
 * [com.example.ottomatic.engine.trigger.VariableChangeTrigger].
 *
 * An object rather than an injected class because it is an event hub as well as
 * a store: [AndroidTriggerHost] subscribes to it while arming, outside any
 * suspending context, exactly as it does for [com.example.ottomatic.core.trigger.TriggerBus].
 * Persistence is attached afterwards by `ServiceLocator` — see [attach].
 *
 * The map is concurrent because a variable is now written from actions running
 * on whatever dispatcher their macro is on, and read synchronously by
 * `value.variable` on the pull side of a different one.
 */
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
     * [com.example.ottomatic.data.GeofencePlaceRepository] applies to its cache.
     */
    fun attach(directory: File, scope: CoroutineScope) {
        val file = File(directory, FILE_NAME)
        storage = Storage(file, scope)
        values.putAll(read(file))
    }

    /** Returns the current value for [name], or null if it has never been set. */
    override fun get(name: String): String? = values[name]

    /**
     * Sets [name] to [value], emitting a [TriggerEvent] and persisting when this
     * actually changes something.
     *
     * The no-op on an unchanged value is load-bearing rather than an
     * optimisation: a macro that re-writes the same variable every minute must
     * not fire a `trigger.variable_change` every minute, or "when the state
     * changes" would mean "whenever anyone looked".
     */
    override fun set(name: String, value: String) {
        if (values.put(name, value) == value) return
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
        storage?.save(values.toMap())
    }

    /** Stream of change events filtered to [name] (used by the host). */
    fun changesFor(name: String): Flow<TriggerEvent> =
        _changes.asSharedFlow().filter { it.payload[KEY_NAME] == name }

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
     * Every write serialises the whole map, so a burst of writes coalesces into
     * whichever ones win the mutex rather than accumulating a queue of diffs.
     */
    private class Storage(private val file: File, private val scope: CoroutineScope) {
        private val mutex = Mutex()

        fun save(snapshot: Map<String, String>) {
            scope.launch {
                mutex.withLock {
                    withContext(Dispatchers.IO) {
                        runCatching { file.writeText(JSON.encodeToString(SERIALIZER, snapshot)) }
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
