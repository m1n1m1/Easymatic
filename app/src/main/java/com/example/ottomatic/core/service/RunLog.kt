package com.example.ottomatic.core.service

import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import java.util.concurrent.atomic.AtomicLong

/**
 * How loud a line is.
 *
 * Declaration order **is** severity order, so the console's filter is a plain
 * `entry.level >= minLevel` and needs no lookup table. [DEBUG] is the trace
 * level: every value read, every transform, every node entered. It is what makes
 * "why did my macro do nothing" answerable, and also what would drown a console
 * that showed it by default — so it is kept and hidden, not dropped.
 */
enum class LogLevel { DEBUG, INFO, WARN, ERROR }

/**
 * Where a line came from.
 *
 * [nodeId] is the raw string rather than a [com.example.ottomatic.core.model.NodeId]
 * so the persisted form stays a plain JSON object; the UI wraps it again when it
 * selects the node.
 *
 * [runId] distinguishes two runs of the same workflow. With `action.delay` in
 * the palette and one coroutine per trigger flow, two runs genuinely interleave
 * in the buffer, and a "run started" line alone cannot untangle them.
 */
@Serializable
data class LogSource(
    val workflowId: String,
    val runId: Long,
    val nodeId: String? = null,
    val nodeName: String? = null,
) {
    companion object {
        /**
         * The run id for a line that belongs to a workflow but to no run — arming a
         * trigger, or failing to. `WorkflowExecutor.runIds` counts up from 1, so this
         * can never collide with a real run.
         */
        const val NO_RUN = 0L
    }
}

/**
 * One line in a workflow's console.
 *
 * [id] exists only to key a `LazyColumn`: a timestamp and a message are not
 * unique — two identical `console.log("x")` calls land in the same millisecond —
 * and a duplicate key crashes the list. It is deliberately **not** persisted, so
 * ids restored from disk cannot collide with ones minted afterwards.
 */
@Serializable
data class LogEntry(
    val level: LogLevel,
    val message: String,
    val source: LogSource? = null,
    val atMs: Long = System.currentTimeMillis(),
    @Transient val id: Long = nextEntryId(),
)

/**
 * The engine's run log, and the console's source.
 *
 * A `core/` port like [Variables] and [SystemServices], with its file half in
 * `data/log/`. Reads are per workflow because that is how the console is scoped:
 * one global flow would recompose an open console every time an unrelated macro
 * logged, and one chatty macro would evict another's history.
 */
interface RunLog {

    /** Records [entry]. Called from the execution path, so it must not block. */
    fun record(entry: LogEntry)

    /**
     * Everything logged for [workflowId], oldest first, bounded.
     *
     * Safe to subscribe to before the workflow has ever run — the flow exists
     * from the first call and starts empty.
     */
    fun entries(workflowId: String): StateFlow<List<LogEntry>>

    /** Drops [workflowId]'s history, in memory and on disk. */
    fun clear(workflowId: String)

    /**
     * Drops the one line [entryId] names, in memory and on disk.
     *
     * Keyed by [LogEntry.id] rather than by the entry, because the entry the
     * console is holding came out of the buffer and is the same object — an
     * identity a value class does not otherwise have, since two identical
     * `console.log("x")` calls in the same millisecond are equal in every
     * persisted field.
     *
     * Silently does nothing when the id is not there: a line can be deleted twice
     * from two places, and the second is already what was asked for.
     */
    fun delete(workflowId: String, entryId: Long)

    /**
     * When [workflowId]'s lines were last all seen, as a wall-clock stamp; `0`
     * when they never were.
     *
     * A watermark rather than a per-entry flag for one reason: it has to survive
     * the process, and [LogEntry.id] deliberately does not. [LogEntry.atMs] is the
     * only identity a restored line keeps, so it is the only thing an
     * acknowledgement can be expressed in.
     */
    fun acknowledged(workflowId: String): StateFlow<Long>

    /**
     * Marks everything recorded for [workflowId] so far as seen, moving the
     * watermark to the newest line.
     *
     * This is what stops a warning nagging forever. The console's badge is there
     * to say "something went wrong that you have not looked at"; once the console
     * is open, the second half is no longer true, and a badge that stays is a
     * badge nobody reads. The lines themselves are untouched — the history is
     * still the history, and [clear] is still the only thing that shortens it.
     */
    fun acknowledge(workflowId: String)

    companion object {
        /**
         * The longest a single line may be, enforced at the sink so it holds for
         * every writer — see `RunLogStore.truncated`.
         *
         * It lives here rather than in `data/log/` because it is not only the
         * sink's business: the executor renders the `in`/`out` lines and has to
         * know how much room a line has before it decides how much of a value to
         * show. Two constants that must agree is exactly the pair that drifts, and
         * the drift is invisible — a value trimmed to 200 characters never reaches
         * the cap, so nothing ever fails to say so.
         *
         * Generous for a deliberate `action.log` of a JSON payload, and far below
         * the point where one line matters. With the entry cap this bounds a
         * workflow's buffer at ~1 MB in the pathological case and ~50 KB in a
         * realistic one.
         */
        const val MAX_MESSAGE_CHARS = 2_000
    }
}

private val entryIds = AtomicLong()

private fun nextEntryId(): Long = entryIds.incrementAndGet()
