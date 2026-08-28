package com.example.ottomatic.data.log

import com.example.ottomatic.core.service.LogEntry
import com.example.ottomatic.core.service.LogLevel
import com.example.ottomatic.core.service.RunLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import java.io.File
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * The engine's run log: a bounded buffer per workflow, plus the disk half.
 *
 * A class rather than an object, unlike [com.example.ottomatic.data.trigger.VariableStore].
 * That one is an object because it doubles as an event hub the trigger host
 * subscribes to from non-suspending arm code; nothing here needs that, and a
 * process-wide object would leak entries between JVM tests — the reason
 * `VariableStore` had to grow a `clear()` test seam.
 *
 * One instance lives in `ServiceLocator` and is shared by the foreground service
 * and the editor's preview run, because both already share one
 * [com.example.ottomatic.engine.ExecutionContext]. That is what makes a
 * background run show up in the editor's console with no wiring between them.
 *
 * Memory: [MAX_ENTRIES_PER_WORKFLOW] × ~150 bytes × workflows that have logged —
 * under two megabytes with twenty armed macros, so buckets are never evicted.
 */
class RunLogStore : RunLog {

    private val buffers = ConcurrentHashMap<String, MutableStateFlow<List<LogEntry>>>()
    private val hydrated = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    private val acks = LogAcknowledgements()

    private var storage: Storage? = null

    /**
     * Starts persisting to `{directory}/logs/`, and lets [entries] restore what
     * is already there.
     *
     * Nothing is read here. An app with twenty macros would otherwise pay twenty
     * file reads at startup to render a list that shows no logs at all; the read
     * happens on the first [entries] call instead, which is the moment a console
     * could actually display it.
     *
     * **[scope] carries the file I/O and must be dispatched accordingly** —
     * `ServiceLocator.appScope` is `Dispatchers.IO`. Nothing here switches
     * dispatchers itself: doing so on top of an IO scope buys nothing, and it
     * would put the writes outside any test scheduler's reach.
     */
    fun attach(directory: File, scope: CoroutineScope) {
        val logs = File(directory, DIR_NAME)
        storage = Storage(logs, scope)
        acks.attach(logs)
    }

    /**
     * Appends [entry], dropping the oldest line once the buffer is full.
     *
     * Called from the execution path, so nothing here touches the filesystem:
     * [LogLevel.INFO] and above are queued for a debounced write, and
     * [LogLevel.DEBUG] traces stay in memory. They are the bulk of the volume and
     * their value is the live edit-and-run loop, where the process is alive by
     * definition.
     */
    override fun record(entry: LogEntry) {
        val workflowId = entry.source?.workflowId ?: UNATTRIBUTED
        val bounded = entry.truncated()
        bufferFor(workflowId).update { (it + bounded).takeLast(MAX_ENTRIES_PER_WORKFLOW) }
        if (bounded.level >= PERSIST_FROM) storage?.queue(workflowId, bounded)
    }

    override fun entries(workflowId: String): StateFlow<List<LogEntry>> {
        val buffer = bufferFor(workflowId)
        hydrate(workflowId, buffer)
        return buffer.asStateFlow()
    }

    /**
     * Writes everything queued, now. Test seam — the app relies on the debounce,
     * which is what keeps file I/O off the execution path.
     */
    internal suspend fun flushNow() {
        storage?.flushAll()
    }

    override fun clear(workflowId: String) {
        bufferFor(workflowId).value = emptyList()
        acks.forget(workflowId)
        storage?.rewrite(workflowId, emptyList())
    }

    /**
     * Removes one line and rewrites what is left.
     *
     * [hydrate] first, so the rewrite is over the whole history rather than over
     * whatever this process happens to have logged — deleting a line from a
     * console that was never opened would otherwise truncate the file to the
     * current run. In practice the console has always hydrated by the time it can
     * offer a delete; this is what makes that a fact rather than an assumption.
     */
    override fun delete(workflowId: String, entryId: Long) {
        val buffer = bufferFor(workflowId)
        hydrate(workflowId, buffer)
        if (buffer.value.none { it.id == entryId }) return
        buffer.update { entries -> entries.filterNot { it.id == entryId } }
        storage?.rewrite(workflowId, buffer.value.filter { it.level >= PERSIST_FROM })
    }

    override fun acknowledged(workflowId: String): StateFlow<Long> {
        hydrate(workflowId, bufferFor(workflowId))
        return acks.of(workflowId)
    }

    /** The newest line there is, is the newest line that has been seen. */
    override fun acknowledge(workflowId: String) {
        val newest = bufferFor(workflowId).value.maxOfOrNull { it.atMs } ?: return
        acks.mark(workflowId, newest)
    }

    /**
     * An entry with no attribution still lands somewhere findable rather than
     * being dropped — nothing in the app reads this bucket, but a log line that
     * silently vanishes is the one bug a log cannot help you diagnose.
     */
    private fun bufferFor(workflowId: String): MutableStateFlow<List<LogEntry>> =
        buffers.computeIfAbsent(workflowId) { MutableStateFlow(emptyList()) }

    /**
     * Prepends what was persisted, once per workflow per process.
     *
     * The read is synchronous, following
     * [com.example.ottomatic.data.GeofencePlaceRepository] and
     * `VariableStore.attach`: one file of at most [MAX_ENTRIES_PER_WORKFLOW]
     * lines, on the thread that opened the console. Doing it asynchronously
     * would mean the console renders "nothing logged yet" for a frame and then
     * fills in, which reads as a bug in exactly the screen a user opens when
     * they already suspect one.
     *
     * Restored entries are cut off at the oldest live one, so a run that logged
     * before the console was ever opened is not shown twice.
     */
    private fun hydrate(workflowId: String, buffer: MutableStateFlow<List<LogEntry>>) {
        // The `takeIf` order matters: an unattached store must not mark the
        // workflow hydrated, or attaching later would never restore it.
        val store = storage?.takeIf { hydrated.add(workflowId) } ?: return
        // Before the entries, so a badge is never drawn for lines the last session
        // already acknowledged, not even for the frame between the two reads.
        acks.restore(workflowId)
        val restored = store.read(workflowId)
        if (restored.isEmpty()) return
        buffer.update { live ->
            val oldestLive = live.firstOrNull()?.atMs ?: Long.MAX_VALUE
            (restored.filter { it.atMs < oldestLive } + live).takeLast(MAX_ENTRIES_PER_WORKFLOW)
        }
    }

    /**
     * The file half, kept separate so the store works unattached — unit tests and
     * previews then get a log that simply does not outlive the process.
     *
     * One JSON-Lines file per workflow, mirroring
     * [com.example.ottomatic.data.WorkflowRepository]'s file-per-item. Lines make
     * the common case an append instead of a rewrite of the whole history.
     */
    private class Storage(private val directory: File, val scope: CoroutineScope) {

        private val mutex = Mutex()
        private val pending = ConcurrentHashMap<String, MutableList<LogEntry>>()
        private val scheduled = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
        private val lineCounts = ConcurrentHashMap<String, Int>()

        /**
         * Queues [entry] and schedules one flush per workflow per window.
         *
         * The debounce is what keeps file I/O off the execution path: a run that
         * logs a dozen lines writes once, not a dozen times.
         */
        fun queue(workflowId: String, entry: LogEntry) {
            pending.computeIfAbsent(workflowId) { Collections.synchronizedList(mutableListOf()) }.add(entry)
            if (!scheduled.add(workflowId)) return
            scope.launch {
                delay(FLUSH_DELAY_MS)
                scheduled.remove(workflowId)
                flush(workflowId)
            }
        }

        fun read(workflowId: String): List<LogEntry> = runCatching {
            val file = fileFor(workflowId)
            if (!file.exists()) return@runCatching emptyList()
            val lines = file.readLines()
            lineCounts[workflowId] = lines.size
            lines.mapNotNull(::decode).takeLast(MAX_ENTRIES_PER_WORKFLOW)
        }.getOrDefault(emptyList())

        /**
         * Replaces the file with exactly [entries] — the one write that is not an
         * append, because removing a line from the middle of a log cannot be one.
         * An empty list deletes it, which is what clearing a console is: the same
         * operation with nothing left over, rather than a second path with its own
         * bookkeeping to keep in step.
         *
         * **Synchronous**, because a deletion the user just asked for must not race
         * the debounced write that is about to append to the same file — and it
         * drops [pending] for a subtler reason: those lines are already in the
         * buffer this list came from, so they are *in* the rewrite. Flushing them
         * afterwards would append them a second time.
         *
         * It is also a compaction, and the only lines it can lose are ones no
         * surface could show: the buffer holds at most [MAX_ENTRIES_PER_WORKFLOW],
         * which is exactly what [read] restores and what the next [compact] would
         * have kept anyway.
         */
        fun rewrite(workflowId: String, entries: List<LogEntry>) {
            pending.remove(workflowId)
            runCatching {
                val file = fileFor(workflowId)
                if (entries.isEmpty()) {
                    lineCounts.remove(workflowId)
                    file.delete()
                    return@runCatching
                }
                file.parentFile?.mkdirs()
                file.writeText(entries.joinToString(separator = "") { encode(it) })
                lineCounts[workflowId] = entries.size
            }
        }

        suspend fun flushAll() = pending.keys.toList().forEach { flush(it) }

        private suspend fun flush(workflowId: String) {
            val batch = pending.remove(workflowId)?.toList().orEmpty()
            if (batch.isEmpty()) return
            mutex.withLock { runCatching { append(workflowId, batch) } }
        }

        private fun append(workflowId: String, batch: List<LogEntry>) {
            val file = fileFor(workflowId)
            file.parentFile?.mkdirs()
            file.appendText(batch.joinToString(separator = "") { encode(it) })
            val count = (lineCounts[workflowId] ?: countLines(file, batch.size)) + batch.size
            lineCounts[workflowId] = if (count > MAX_ENTRIES_PER_WORKFLOW * COMPACT_AT) compact(file) else count
        }

        /**
         * Rewrites the file down to the cap, so an append-only log cannot grow
         * without bound. Deferred until it is worth doing — compacting on every
         * flush would rewrite the whole history for each line.
         */
        private fun compact(file: File): Int {
            val kept = file.readLines().filter(String::isNotBlank).takeLast(MAX_ENTRIES_PER_WORKFLOW)
            file.writeText(kept.joinToString(separator = "\n", postfix = "\n"))
            return kept.size
        }

        /** Only reached once per workflow per process, when nothing hydrated it. */
        private fun countLines(file: File, appended: Int): Int =
            runCatching { file.readLines().size - appended }.getOrDefault(0).coerceAtLeast(0)

        private fun fileFor(workflowId: String) = File(directory, "$workflowId$SUFFIX")
    }

    companion object {
        /** Enough to cover several runs of a busy macro without unbounded growth. */
        const val MAX_ENTRIES_PER_WORKFLOW = 500

        /** Below this, a line lives and dies in memory. */
        val PERSIST_FROM = LogLevel.INFO

        private const val UNATTRIBUTED = ""
        private const val DIR_NAME = "logs"
        private const val SUFFIX = ".jsonl"
        private const val FLUSH_DELAY_MS = 500L
        private const val COMPACT_AT = 2
    }
}

/**
 * Caps one line's length, so the buffer's entry count is a real bound on its size
 * rather than a bound on nothing.
 *
 * This is the **only** cut. `action.log`'s message is `@Wired`, so pointing an HTTP
 * response at it logs the whole body — at INFO, which is persisted — and the
 * executor's `in`/`out` lines carry whatever crossed a wire. Capping at the sink
 * rather than at each call site is what makes the limit hold for every writer,
 * including the next one, and it is what lets a writer hand over everything it has:
 * a value cut short on the way in is cut short in the entry overlay too, which is
 * the one surface that exists to show a line whole.
 */
private fun LogEntry.truncated(): LogEntry =
    if (message.length <= RunLog.MAX_MESSAGE_CHARS) this
    else copy(message = message.take(RunLog.MAX_MESSAGE_CHARS) + "… (${message.length} chars)")

/** One JSON-Lines line, newline included, so an append and a rewrite agree on it. */
private fun encode(entry: LogEntry): String = JSON.encodeToString(LogEntry.serializer(), entry) + "\n"

/** `null` rather than throwing: one half-written line must not cost the history. */
private fun decode(line: String): LogEntry? =
    if (line.isBlank()) null
    else runCatching { JSON.decodeFromString(LogEntry.serializer(), line) }.getOrNull()

/**
 * `encodeDefaults` is load-bearing, not tidiness. [LogEntry.atMs] carries a
 * default, so without it the timestamp is simply not written — and a restored entry
 * then decodes back to *its own* default, meaning every line from last night reads
 * as having happened the moment the console was opened. That is precisely the
 * question persistence exists to answer, and nothing fails loudly when it is wrong.
 */
private val JSON = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}
